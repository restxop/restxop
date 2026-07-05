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
package dev.restxop.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.restxop.RestxopConfig;
import dev.restxop.core.internal.buffer.FileSpoolStorage;
import dev.restxop.core.internal.exchange.Exchange;
import dev.restxop.core.internal.read.MessageReader;
import dev.restxop.core.internal.write.MessageWriter;
import dev.restxop.jackson2.Jackson2RootPartCodec;
import dev.restxop.jackson3.Jackson3RootPartCodec;
import dev.restxop.spi.ResolvableTypeInfo;
import dev.restxop.spi.RootPartCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the SC-005 cross-generation suite with both codec stacks side by
 * side in one JVM: Jackson 2 (the Boot 3 generation) as A, Jackson 3
 * (the Boot 4 generation) as B.
 */
class CrossGenerationTest extends CrossGenerationSuite {

    private final RootPartCodec codecA = new Jackson2RootPartCodec(new ObjectMapper());
    private final RootPartCodec codecB = new Jackson3RootPartCodec(JsonMapper.builder().build());
    private final List<MessageReader> openReaders = new ArrayList<>();

    @AfterEach
    void closeReaders() {
        openReaders.forEach(MessageReader::close);
        openReaders.clear();
    }

    private RestxopConformanceSuite.EncodedMessage encode(RootPartCodec codec, Object payload,
            RestxopConformanceSuite.WriterSettings settings) {
        Iterator<String> ids = settings.attachmentContentIds().iterator();
        MessageWriter writer = new MessageWriter(RestxopConfig.defaults(), codec,
                new MessageWriter.WriterIds(settings.boundary(), settings.rootContentId(),
                        ids::next));
        Exchange exchange = Exchange.open(RestxopConfig.defaults(), List.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writer.write(exchange, payload, out);
            exchange.complete();
        } catch (IOException e) {
            exchange.fail(e);
            throw new UncheckedIOException(e);
        }
        return new RestxopConformanceSuite.EncodedMessage(writer.contentType(), out.toByteArray());
    }

    private <T> T decode(RootPartCodec codec, String contentType, InputStream body, Type type) {
        MessageReader reader = new MessageReader(RestxopConfig.defaults(), codec,
                new FileSpoolStorage(), List.of());
        openReaders.add(reader);
        return reader.<T>read(contentType, body, ResolvableTypeInfo.of(type), null).payload();
    }

    @Override
    protected RestxopConformanceSuite.EncodedMessage encodeGenerationA(Object payload,
            RestxopConformanceSuite.WriterSettings settings) {
        return encode(codecA, payload, settings);
    }

    @Override
    protected RestxopConformanceSuite.EncodedMessage encodeGenerationB(Object payload,
            RestxopConformanceSuite.WriterSettings settings) {
        return encode(codecB, payload, settings);
    }

    @Override
    protected <T> T decodeGenerationA(String contentType, InputStream body, Type type) {
        return decode(codecA, contentType, body, type);
    }

    @Override
    protected <T> T decodeGenerationB(String contentType, InputStream body, Type type) {
        return decode(codecB, contentType, body, type);
    }
}
