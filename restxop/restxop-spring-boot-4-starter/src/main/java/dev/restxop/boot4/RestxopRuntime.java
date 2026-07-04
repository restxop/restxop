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
package dev.restxop.boot4;

import dev.restxop.RestxopConfig;
import dev.restxop.core.internal.exchange.Exchange;
import dev.restxop.core.internal.read.MessageReader;
import dev.restxop.core.internal.read.ReadResult;
import dev.restxop.core.internal.write.MessageWriter;
import dev.restxop.spi.ExchangeListener;
import dev.restxop.spi.ResolvableTypeInfo;
import dev.restxop.spi.RootPartCodec;
import dev.restxop.spi.SpoolStorage;
import java.io.InputStream;
import java.util.List;
import java.util.function.Supplier;

/**
 * One shared restxop engine per application context: configuration
 * snapshot, codec, and the message reader with its bounded drain pool.
 * Registered as an {@code AutoCloseable} bean so the pool shuts down with
 * the context.
 */
public final class RestxopRuntime implements AutoCloseable {

    private final RestxopConfig config;
    private final RootPartCodec codec;
    private final List<ExchangeListener> listeners;
    private final MessageReader reader;
    private final Supplier<MessageWriter.WriterIds> writerIds;

    public RestxopRuntime(RestxopConfig config, RootPartCodec codec, SpoolStorage spoolStorage,
            List<ExchangeListener> listeners) {
        this(config, codec, spoolStorage, listeners, MessageWriter.WriterIds::random);
    }

    /** Test seam: deterministic boundary/Content-ID generation. */
    public RestxopRuntime(RestxopConfig config, RootPartCodec codec, SpoolStorage spoolStorage,
            List<ExchangeListener> listeners, Supplier<MessageWriter.WriterIds> writerIds) {
        this.config = config;
        this.codec = codec;
        this.listeners = List.copyOf(listeners);
        this.reader = new MessageReader(config, codec, spoolStorage, listeners);
        this.writerIds = writerIds;
    }

    public RestxopConfig config() {
        return config;
    }

    public RootPartCodec codec() {
        return codec;
    }

    public boolean supportsMediaType(String mediaType) {
        return reader.supportsMediaType(mediaType);
    }

    public boolean canHandle(ResolvableTypeInfo type) {
        return codec.canHandle(type);
    }

    public <T> ReadResult<T> read(String contentType, InputStream body, ResolvableTypeInfo type,
            AutoCloseable upstreamRelease) {
        return reader.read(contentType, body, type, upstreamRelease);
    }

    /** A single-use writer for one outgoing message. */
    public MessageWriter newWriter() {
        return new MessageWriter(config, codec, writerIds.get());
    }

    public Exchange openExchange() {
        return Exchange.open(config, listeners);
    }

    @Override
    public void close() {
        reader.close();
    }
}
