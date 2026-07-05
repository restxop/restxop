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
package dev.restxop.boot3.feign;

import dev.restxop.boot3.RestxopRuntime;
import dev.restxop.core.internal.mime.ContentTypeParams;
import dev.restxop.spi.ResolvableTypeInfo;
import feign.Response;
import feign.codec.Decoder;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * Feign decoder with deferred close (FR-024/FR-027): attachment messages
 * are read through the restxop engine, which holds the response open until
 * the drain reaches end-of-message (or the exchange fails or expires) and
 * keeps attachments readable from library buffers afterwards. Everything
 * else delegates to the regular Spring decoder chain and is closed here —
 * the accompanying builder customizer disables Feign's own close-after-
 * decode so the decoder owns response lifetime either way.
 */
public class RestxopFeignDecoder implements Decoder {

    private final RestxopRuntime runtime;
    private final Decoder delegate;

    public RestxopFeignDecoder(RestxopRuntime runtime, Decoder delegate) {
        this.runtime = runtime;
        this.delegate = delegate;
    }

    @Override
    public Object decode(Response response, Type type) throws IOException {
        String contentType = contentType(response);
        if (contentType != null && response.body() != null
                && runtime.supportsMediaType(ContentTypeParams.parse(contentType).mediaType())
                && runtime.canHandle(ResolvableTypeInfo.of(type))) {
            return runtime.read(contentType, response.body().asInputStream(),
                    ResolvableTypeInfo.of(type), response::close).payload();
        }
        try {
            return delegate.decode(response, type);
        } finally {
            response.close();
        }
    }

    private static String contentType(Response response) {
        for (Map.Entry<String, Collection<String>> header : response.headers().entrySet()) {
            if ("content-type".equals(header.getKey().toLowerCase(Locale.ROOT))
                    && !header.getValue().isEmpty()) {
                return header.getValue().iterator().next();
            }
        }
        return null;
    }
}
