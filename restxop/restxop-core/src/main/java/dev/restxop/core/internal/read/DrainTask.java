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

import dev.restxop.AttachmentUnavailableException;
import dev.restxop.ExchangeFailedException;
import dev.restxop.LimitExceededException;
import dev.restxop.MalformedMessageException;
import dev.restxop.RestxopConfig;
import dev.restxop.RestxopException;
import dev.restxop.core.internal.buffer.ChaseBuffer;
import dev.restxop.core.internal.exchange.Exchange;
import dev.restxop.core.internal.mime.DelimiterScanner;
import dev.restxop.core.internal.mime.PartHeaders;
import dev.restxop.spi.SpoolStorage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The eager drain (research R1): consumes the remainder of the message at
 * wire speed on a pool worker — or, when the pool is saturated, on the first
 * consumer's own thread (caller-runs fallback) — writing each referenced
 * part into its chase buffer, skipping unreferenced parts with a warning,
 * and releasing the upstream transport the moment end-of-message is
 * reached, regardless of consumer pace.
 *
 * <p>Also owns the read-side per-exchange registry: the resolver creates one
 * exchange-backed attachment (and its chase buffer) per distinct Content-ID
 * at payload-deserialization time, so consumer waits all funnel through
 * buffer conditions and drain failure can poison every blocked reader.</p>
 */
final class DrainTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DrainTask.class);

    private final Exchange exchange;
    private final RestxopConfig config;
    private final DelimiterScanner scanner;
    private final SpoolStorage spoolStorage;
    private final Runnable upstreamRelease;

    private final Map<String, ExchangeAttachment> placeholders = new ConcurrentHashMap<>();
    private final AtomicInteger unfinished = new AtomicInteger();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean callerRunsPending;

    DrainTask(Exchange exchange, RestxopConfig config, DelimiterScanner scanner,
            SpoolStorage spoolStorage, Runnable upstreamRelease) {
        this.exchange = exchange;
        this.config = config;
        this.scanner = scanner;
        this.spoolStorage = spoolStorage;
        this.upstreamRelease = upstreamRelease;
    }

    /** Read-side resolver: same Content-ID → same shared instance (FR-012). */
    ExchangeAttachment resolve(String contentId) {
        return placeholders.computeIfAbsent(contentId, cid -> {
            ChaseBuffer buffer = new ChaseBuffer(exchange.id(), cid,
                    config.memoryWindowPerPart(), config.spoolMaxPerAttachment(),
                    config.readWait(),
                    () -> spoolStorage.createOverflow(exchange.id(), cid, exchange.config()),
                    total -> {
                        ExchangeAttachment attachment = placeholders.get(cid);
                        if (attachment != null) {
                            exchange.recordSpooled(attachment, total);
                        }
                    });
            exchange.registerBuffer(buffer);
            unfinished.incrementAndGet();
            return new ExchangeAttachment(cid, buffer, this);
        });
    }

    boolean hasReferences() {
        return !placeholders.isEmpty();
    }

    /** Marks that pool submission was rejected: the first consumer read drives the drain. */
    void markCallerRunsPending() {
        callerRunsPending = true;
        exchange.drainState(Exchange.DrainState.QUEUED);
    }

    /** Caller-runs fallback entry point, invoked from attachment stream reads. */
    void ensureRunning() {
        if (callerRunsPending && started.compareAndSet(false, true)) {
            exchange.drainState(Exchange.DrainState.CALLER_RUNS);
            drain();
        }
    }

    /** Synchronous drain on the caller thread (zero-reference messages). */
    void runOnCallerThread() {
        if (started.compareAndSet(false, true)) {
            exchange.drainState(Exchange.DrainState.CALLER_RUNS);
            drain();
        }
    }

    @Override
    public void run() {
        if (started.compareAndSet(false, true)) {
            exchange.drainState(Exchange.DrainState.RUNNING);
            drain();
        }
    }

    private void drain() {
        try {
            int partCount = 1; // the root part is already consumed
            InputStream part;
            byte[] copyBuffer = new byte[config.readBufferSize()];
            while ((part = scanner.nextPart()) != null) {
                exchange.checkTtl();
                if (++partCount > config.maxParts()) {
                    throw new LimitExceededException(exchange.id(), "limits.max-parts",
                            config.maxParts(), "message exceeds the part-count bound");
                }
                PartHeaders headers = PartHeaders.parse(part, config.maxPartHeaderBytes(),
                        exchange.id());
                String contentId = headers.contentId().orElseThrow(
                        () -> new MalformedMessageException(exchange.id(),
                                "attachment part has no Content-ID header"));
                ExchangeAttachment attachment = placeholders.get(contentId);
                if (attachment == null) {
                    log.warn("[exchange {}] skipping part '{}': not referenced by the payload",
                            exchange.id(), contentId);
                    skip(part, copyBuffer);
                    continue;
                }
                if (attachment.buffer().isWriterComplete()) {
                    throw new MalformedMessageException(exchange.id(),
                            "duplicate Content-ID '" + contentId + "' on the wire");
                }
                attachment.bindMetadata(headers.filename(), headers.contentType());
                copyPart(part, attachment.buffer(), copyBuffer);
            }
            // End of message: parts that never arrived are unavailable, not hangs
            for (ExchangeAttachment attachment : placeholders.values()) {
                if (!attachment.buffer().isWriterComplete()) {
                    attachment.buffer().poison(new AttachmentUnavailableException(exchange.id(),
                            "message ended without a part for referenced attachment '"
                                    + attachment.contentId() + "'"));
                }
            }
            exchange.drainState(Exchange.DrainState.DONE);
            releaseUpstream();
            maybeComplete();
        } catch (Throwable t) {
            exchange.drainState(Exchange.DrainState.FAILED);
            RestxopException typed = toTyped(t);
            exchange.fail(typed);
        }
    }

    private void copyPart(InputStream part, ChaseBuffer buffer, byte[] copyBuffer)
            throws IOException {
        int n;
        while ((n = part.read(copyBuffer, 0, copyBuffer.length)) != -1) {
            if (n > 0) {
                buffer.write(copyBuffer, 0, n);
            }
            exchange.checkTtl();
        }
        buffer.completeWriter();
    }

    private static void skip(InputStream part, byte[] copyBuffer) throws IOException {
        while (part.read(copyBuffer, 0, copyBuffer.length) != -1) {
            // discard
        }
    }

    /** Called once per attachment when its stream is exhausted or closed early. */
    void attachmentFinished(ExchangeAttachment attachment) {
        exchange.attachmentConsumed(attachment);
        unfinished.decrementAndGet();
        maybeComplete();
    }

    private void maybeComplete() {
        if (unfinished.get() == 0 && exchange.drainState() == Exchange.DrainState.DONE) {
            exchange.complete();
        }
    }

    private void releaseUpstream() {
        try {
            upstreamRelease.run();
        } catch (RuntimeException e) {
            log.warn("[exchange {}] error releasing upstream transport", exchange.id(), e);
        }
    }

    private RestxopException toTyped(Throwable t) {
        if (t instanceof RestxopException restxop) {
            return restxop;
        }
        if (t instanceof IOException io) {
            return new ExchangeFailedException(exchange.id(),
                    "transport failure while draining the message", io);
        }
        return new ExchangeFailedException(exchange.id(), "message drain failed", t);
    }
}
