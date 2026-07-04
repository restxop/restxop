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
package dev.restxop.boot4.web;

import dev.restxop.boot4.RestxopRuntime;
import dev.restxop.boot4.client.DeferredCloseClientHttpRequestFactory;
import dev.restxop.core.internal.exchange.Exchange;
import dev.restxop.core.internal.write.MessageWriter;
import dev.restxop.spi.ResolvableTypeInfo;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.List;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.converter.GenericHttpMessageConverter;

/**
 * MVC/client converter for {@code multipart/related} attachment messages.
 * Claims payload types with reachable {@code Attachment} properties (cached
 * codec introspection) at the restxop media types; reading returns the
 * payload as soon as the root part is deserialized while the drain streams
 * the attachments behind it.
 */
public class RestxopHttpMessageConverter implements GenericHttpMessageConverter<Object> {

    public static final MediaType MULTIPART_RELATED = new MediaType("multipart", "related");
    public static final MediaType COMPOSITE_RELATED = new MediaType("composite", "related");

    private final RestxopRuntime runtime;

    public RestxopHttpMessageConverter(RestxopRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
        if (runtime.config().legacyCompatEnabled()) {
            return List.of(MULTIPART_RELATED, COMPOSITE_RELATED);
        }
        return List.of(MULTIPART_RELATED);
    }

    private boolean supportsMedia(MediaType mediaType) {
        if (mediaType == null) {
            return true; // decided by type support; media type resolved later
        }
        return getSupportedMediaTypes().stream().anyMatch(m -> m.includes(mediaType)
                || mediaType.includes(m));
    }

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return canRead(clazz, null, mediaType);
    }

    @Override
    public boolean canRead(Type type, Class<?> contextClass, MediaType mediaType) {
        return supportsMedia(mediaType) && runtime.canHandle(ResolvableTypeInfo.of(type));
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return supportsMedia(mediaType) && runtime.canHandle(ResolvableTypeInfo.of(clazz));
    }

    @Override
    public boolean canWrite(Type type, Class<?> clazz, MediaType mediaType) {
        return supportsMedia(mediaType)
                && runtime.canHandle(ResolvableTypeInfo.of(type != null ? type : clazz));
    }

    @Override
    public Object read(Class<?> clazz, HttpInputMessage inputMessage) throws IOException {
        return read(clazz, null, inputMessage);
    }

    @Override
    public Object read(Type type, Class<?> contextClass, HttpInputMessage inputMessage)
            throws IOException {
        MediaType contentType = inputMessage.getHeaders().getContentType();
        String contentTypeValue = contentType != null ? contentType.toString() : null;
        // Client responses support deferred close (FR-024): the drain owns
        // the response lifetime; server requests are container-managed
        AutoCloseable upstreamRelease =
                DeferredCloseClientHttpRequestFactory.takeOwnership(inputMessage);
        return runtime.read(contentTypeValue, inputMessage.getBody(),
                ResolvableTypeInfo.of(type), upstreamRelease).payload();
    }

    @Override
    public void write(Object payload, MediaType contentType, HttpOutputMessage outputMessage)
            throws IOException {
        write(payload, payload.getClass(), contentType, outputMessage);
    }

    @Override
    public void write(Object payload, Type type, MediaType contentType,
            HttpOutputMessage outputMessage) throws IOException {
        MessageWriter writer = runtime.newWriter();
        outputMessage.getHeaders().set("Content-Type", writer.contentType());
        if (outputMessage instanceof StreamingHttpOutputMessage streaming) {
            streaming.setBody(body -> writeBody(writer, payload, body));
        } else {
            writeBody(writer, payload, outputMessage.getBody());
        }
    }

    private void writeBody(MessageWriter writer, Object payload, OutputStream body)
            throws IOException {
        Exchange exchange = runtime.openExchange();
        try {
            writer.write(exchange, payload, body);
            exchange.complete();
        } catch (IOException | RuntimeException e) {
            exchange.fail(e);
            throw e;
        }
    }
}
