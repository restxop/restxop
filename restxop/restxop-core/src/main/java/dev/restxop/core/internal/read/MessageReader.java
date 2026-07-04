/*
 * Copyright 2026 the restxop contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.restxop.core.internal.read;

import dev.restxop.ExchangeFailedException;
import dev.restxop.LimitExceededException;
import dev.restxop.MalformedMessageException;
import dev.restxop.RestxopConfig;
import dev.restxop.RestxopException;
import dev.restxop.core.internal.exchange.Exchange;
import dev.restxop.core.internal.mime.ContentTypeParams;
import dev.restxop.core.internal.mime.DelimiterScanner;
import dev.restxop.core.internal.mime.IdNormalizer;
import dev.restxop.core.internal.mime.PartHeaders;
import dev.restxop.spi.ExchangeListener;
import dev.restxop.spi.ResolvableTypeInfo;
import dev.restxop.spi.RootPartCodec;
import dev.restxop.spi.SpoolStorage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Read-path entry point (research R1, eager-chase model). {@link #read}
 * parses and deserializes the root part synchronously on the caller's
 * thread and returns the payload immediately; a {@link DrainTask} then
 * consumes the rest of the message at wire speed on the bounded drain pool
 * (caller-runs fallback on saturation), and consumers chase it through
 * per-part buffers. One instance per application; thread-safe.
 */
public final class MessageReader implements AutoCloseable {

    private static final String STANDARD_MEDIA_TYPE = "multipart/related";
    private static final String LEGACY_MEDIA_TYPE = "composite/related";

    private final RestxopConfig config;
    private final RootPartCodec codec;
    private final SpoolStorage spoolStorage;
    private final List<ExchangeListener> listeners;
    private final ThreadPoolExecutor drainPool;

    public MessageReader(RestxopConfig config, RootPartCodec codec, SpoolStorage spoolStorage,
            List<ExchangeListener> listeners) {
        this.config = config;
        this.codec = codec;
        this.spoolStorage = spoolStorage;
        this.listeners = List.copyOf(listeners);
        AtomicInteger threadCounter = new AtomicInteger();
        this.drainPool = new ThreadPoolExecutor(0, config.drainPoolSize(),
                60, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable,
                            "restxop-drain-" + threadCounter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** Whether this reader accepts the given outer media type. */
    public boolean supportsMediaType(String mediaType) {
        return STANDARD_MEDIA_TYPE.equalsIgnoreCase(mediaType)
                || (config.legacyCompatEnabled() && LEGACY_MEDIA_TYPE.equalsIgnoreCase(mediaType));
    }

    /**
     * Reads one message: returns as soon as the root part is deserialized.
     *
     * @param upstreamRelease released the moment the drain reaches
     *        end-of-message — or on failure/TTL — whichever comes first
     *        (FR-024); may be null when the transport needs no deferred close
     */
    public <T> ReadResult<T> read(String contentType, InputStream body, ResolvableTypeInfo type,
            AutoCloseable upstreamRelease) {
        MessageDescriptor descriptor = parseDescriptor(contentType);
        Exchange exchange = Exchange.open(config, listeners);
        IdempotentRelease release = new IdempotentRelease(upstreamRelease);
        exchange.registerResource(release::run);
        try {
            DelimiterScanner scanner = new DelimiterScanner(body, descriptor.boundary(),
                    config.readBufferSize(), exchange.id());
            InputStream rootPart = scanner.nextPart();
            if (rootPart == null) {
                throw new MalformedMessageException(exchange.id(), "message contains no parts");
            }
            PartHeaders rootHeaders = PartHeaders.parse(rootPart, config.maxPartHeaderBytes(),
                    exchange.id());
            String rootId = rootHeaders.contentId().orElseThrow(
                    () -> new MalformedMessageException(exchange.id(),
                            "root part has no Content-ID header"));
            if (!rootId.equals(descriptor.startId())) {
                throw new MalformedMessageException(exchange.id(),
                        "first part Content-ID '" + rootId + "' does not match start '"
                                + descriptor.startId() + "'");
            }
            String rootContentType = rootHeaders.contentType().orElseThrow(
                    () -> new MalformedMessageException(exchange.id(),
                            "root part has no Content-Type header"));
            if (!"application/json".equals(ContentTypeParams.parse(rootContentType).mediaType())) {
                throw new MalformedMessageException(exchange.id(),
                        "root part Content-Type must be application/json, was '"
                                + rootContentType + "'");
            }

            DrainTask drain = new DrainTask(exchange, config, scanner, spoolStorage, release::run);
            T payload = deserializeRoot(exchange, rootPart, type, drain);
            exchange.payloadDelivered();

            if (!drain.hasReferences()) {
                // Zero-attachment fast path: finish on the caller thread (R1)
                drain.runOnCallerThread();
                if (exchange.state() == Exchange.State.OPEN) {
                    exchange.complete();
                }
                exchange.failureCause().ifPresent(cause -> {
                    throw cause instanceof RestxopException restxop ? restxop
                            : new ExchangeFailedException(exchange.id(), "drain failed", cause);
                });
            } else {
                submitDrain(drain);
            }
            return new ReadResult<>(payload, exchange);
        } catch (RestxopException e) {
            exchange.fail(e);
            throw e;
        } catch (IOException e) {
            ExchangeFailedException typed = new ExchangeFailedException(exchange.id(),
                    "transport failure while reading the message", e);
            exchange.fail(typed);
            throw typed;
        } catch (RuntimeException e) {
            MalformedMessageException typed = new MalformedMessageException(exchange.id(),
                    "root payload deserialization failed: " + e.getMessage(), e);
            exchange.fail(typed);
            throw typed;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T deserializeRoot(Exchange exchange, InputStream rootPart, ResolvableTypeInfo type,
            DrainTask drain) {
        BoundedInputStream bounded = new BoundedInputStream(rootPart,
                config.maxRootPartBytes(), exchange.id());
        return (T) codec.readRoot(bounded, type, drain::resolve);
    }

    private void submitDrain(DrainTask drain) {
        try {
            drainPool.execute(drain);
        } catch (RejectedExecutionException e) {
            // Saturation degrades gracefully: the first consumer read drives
            // the drain on its own thread (R1 caller-runs fallback)
            drain.markCallerRunsPending();
        }
    }

    private MessageDescriptor parseDescriptor(String contentType) {
        ContentTypeParams outer = ContentTypeParams.parse(contentType);
        if (!supportsMediaType(outer.mediaType())) {
            throw new MalformedMessageException(
                    "unsupported media type '" + outer.mediaType() + "'");
        }
        String typeParam = outer.requiredParameter("type");
        if (!"application/json".equalsIgnoreCase(typeParam.trim())) {
            throw new MalformedMessageException(
                    "type parameter must be application/json, was '" + typeParam + "'");
        }
        String boundary = outer.requiredParameter("boundary");
        if (boundary.isEmpty()) {
            throw new MalformedMessageException("boundary parameter is empty");
        }
        String startId = IdNormalizer.normalize(outer.requiredParameter("start"));
        return new MessageDescriptor(boundary, startId);
    }

    @Override
    public void close() {
        drainPool.shutdownNow();
    }

    private record MessageDescriptor(String boundary, String startId) {
    }

    private static final class IdempotentRelease {

        private final AutoCloseable delegate;
        private final AtomicBoolean released = new AtomicBoolean();

        IdempotentRelease(AutoCloseable delegate) {
            this.delegate = delegate;
        }

        void run() {
            if (delegate != null && released.compareAndSet(false, true)) {
                try {
                    delegate.close();
                } catch (Exception e) {
                    throw e instanceof RuntimeException runtime ? runtime
                            : new IllegalStateException("upstream release failed", e);
                }
            }
        }
    }

    /** Enforces {@code limits.max-root-part-bytes} while the codec reads the root. */
    private static final class BoundedInputStream extends InputStream {

        private final InputStream delegate;
        private final long max;
        private final String exchangeId;
        private long count;

        BoundedInputStream(InputStream delegate, long max, String exchangeId) {
            this.delegate = delegate;
            this.max = max;
            this.exchangeId = exchangeId;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                bump(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                bump(n);
            }
            return n;
        }

        private void bump(int n) {
            count += n;
            if (count > max) {
                throw new LimitExceededException(exchangeId, "limits.max-root-part-bytes", max,
                        "root part exceeds the configured bound");
            }
        }
    }
}
