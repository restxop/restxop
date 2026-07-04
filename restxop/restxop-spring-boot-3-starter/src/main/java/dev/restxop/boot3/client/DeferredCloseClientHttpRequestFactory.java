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
package dev.restxop.boot3.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Deferred-close support (FR-024): wraps a request factory so responses can
 * be handed over to a restxop exchange. When the restxop converter reads an
 * attachment message it takes ownership of the response — the client's own
 * {@code close()} (which fires as soon as the payload is extracted) becomes
 * a no-op, and the real close happens when the drain reaches end-of-message
 * or the exchange fails or expires, whichever comes first.
 */
public final class DeferredCloseClientHttpRequestFactory implements ClientHttpRequestFactory {

    /**
     * Same-thread rendezvous between {@code execute()} and the converter's
     * {@code read()}, which Spring invokes synchronously in the same call
     * frame (often behind body wrappers that hide the response object).
     * This is call-frame plumbing, not exchange state: it is set by
     * execute(), consumed by the first restxop read, and unconditionally
     * cleared when the client closes the response — no state survives the
     * call.
     */
    private static final ThreadLocal<DeferredCloseResponse> CURRENT = new ThreadLocal<>();

    private final ClientHttpRequestFactory delegate;

    public DeferredCloseClientHttpRequestFactory(ClientHttpRequestFactory delegate) {
        this.delegate = delegate;
    }

    /**
     * Takes ownership of the in-flight deferred-close-capable response,
     * returning the release handle the exchange will invoke — or null when
     * the message source does not support (or need) deferred close, e.g.
     * server-side requests owned by the servlet container.
     */
    public static AutoCloseable takeOwnership(HttpInputMessage message) {
        if (message instanceof DeferredCloseResponse deferred) {
            CURRENT.remove();
            return deferred.takeOwnership();
        }
        DeferredCloseResponse current = CURRENT.get();
        if (current != null) {
            CURRENT.remove();
            return current.takeOwnership();
        }
        return null;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
        ClientHttpRequest request = delegate.createRequest(uri, httpMethod);
        return new DeferredCloseRequest(request);
    }

    private static final class DeferredCloseRequest
            implements ClientHttpRequest, StreamingHttpOutputMessage {

        private final ClientHttpRequest delegate;

        DeferredCloseRequest(ClientHttpRequest delegate) {
            this.delegate = delegate;
        }

        /**
         * Preserves the delegate's streaming capability (FR-013): hiding it
         * would push message writers onto the buffered getBody() path.
         */
        @Override
        public void setBody(Body body) {
            if (delegate instanceof StreamingHttpOutputMessage streaming) {
                streaming.setBody(body);
                return;
            }
            try {
                body.writeTo(delegate.getBody());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public ClientHttpResponse execute() throws IOException {
            DeferredCloseResponse response = new DeferredCloseResponse(delegate.execute());
            CURRENT.set(response);
            return response;
        }

        @Override
        public java.io.OutputStream getBody() throws IOException {
            return delegate.getBody();
        }

        @Override
        public HttpMethod getMethod() {
            return delegate.getMethod();
        }

        @Override
        public URI getURI() {
            return delegate.getURI();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }
    }

    static final class DeferredCloseResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final AtomicBoolean owned = new AtomicBoolean();

        DeferredCloseResponse(ClientHttpResponse delegate) {
            this.delegate = delegate;
        }

        /** Transfers close responsibility from the HTTP client to the exchange. */
        AutoCloseable takeOwnership() {
            owned.set(true);
            return delegate::close;
        }

        @Override
        public void close() {
            CURRENT.remove();
            if (!owned.get()) {
                delegate.close();
            }
            // Owned: the exchange releases the real response at drain
            // completion, failure, or TTL expiry
        }

        @Override
        public InputStream getBody() throws IOException {
            return delegate.getBody();
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }
    }
}
