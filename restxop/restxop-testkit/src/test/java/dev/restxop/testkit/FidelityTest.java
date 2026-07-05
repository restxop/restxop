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
import dev.restxop.spi.ResolvableTypeInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;

/**
 * Executes the fidelity suite against the core engine with a real
 * serializer (Jackson 2) so traversal-driven discovery over nested,
 * collection, map, and inherited fields is genuinely exercised
 * (quickstart §4: {@code mvn -pl restxop-testkit -Dgroups=fidelity verify}).
 */
class FidelityTest extends FidelitySuite {

    private final Jackson2RootPartCodec codec = new Jackson2RootPartCodec(new ObjectMapper());
    private final List<MessageReader> openReaders = new ArrayList<>();

    @AfterEach
    void closeReaders() {
        openReaders.forEach(MessageReader::close);
        openReaders.clear();
    }

    @Override
    protected RestxopConformanceSuite.EncodedMessage encode(Object payload,
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

    @Override
    protected <T> T decode(String contentType, InputStream body, Type type) {
        MessageReader reader = new MessageReader(RestxopConfig.defaults(), codec,
                new FileSpoolStorage(), List.of());
        openReaders.add(reader);
        return reader.<T>read(contentType, body, ResolvableTypeInfo.of(type), null).payload();
    }
}
