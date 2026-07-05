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

import tools.jackson.databind.json.JsonMapper;
import dev.restxop.RestxopConfig;
import dev.restxop.core.internal.buffer.FileSpoolStorage;
import dev.restxop.core.internal.exchange.Exchange;
import dev.restxop.core.internal.write.MessageWriter;
import dev.restxop.jackson3.Jackson3RootPartCodec;
import dev.restxop.spi.ResolvableTypeInfo;
import dev.restxop.testkit.FidelitySuite;
import dev.restxop.testkit.RestxopConformanceSuite;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;

/** US3 fidelity suite on the Boot 4 generation (Jackson 3 codec). */
class Boot4FidelityTest extends FidelitySuite {

    private final List<RestxopRuntime> openRuntimes = new ArrayList<>();

    @AfterEach
    void closeRuntimes() {
        openRuntimes.forEach(RestxopRuntime::close);
        openRuntimes.clear();
    }

    private RestxopRuntime runtime(RestxopConformanceSuite.WriterSettings settings) {
        Iterator<String> ids = settings.attachmentContentIds().iterator();
        RestxopRuntime runtime = new RestxopRuntime(RestxopConfig.defaults(),
                new Jackson3RootPartCodec(JsonMapper.builder().build()), new FileSpoolStorage(), List.of(),
                () -> new MessageWriter.WriterIds(settings.boundary(), settings.rootContentId(),
                        ids::next));
        openRuntimes.add(runtime);
        return runtime;
    }

    @Override
    protected RestxopConformanceSuite.EncodedMessage encode(Object payload,
            RestxopConformanceSuite.WriterSettings settings) {
        RestxopRuntime runtime = runtime(settings);
        MessageWriter writer = runtime.newWriter();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Exchange exchange = runtime.openExchange();
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
        return runtime(RestxopConformanceSuite.WriterSettings.fixture())
                .<T>read(contentType, body, ResolvableTypeInfo.of(type), null)
                .payload();
    }
}
