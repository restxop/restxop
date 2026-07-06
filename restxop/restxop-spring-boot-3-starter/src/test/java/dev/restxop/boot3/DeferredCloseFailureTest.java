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
package dev.restxop.boot3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.restxop.RestxopException;
import dev.restxop.testkit.model.ReportPayload;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * FR-024 failure paths: the client's HTTP response, once handed to a
 * restxop exchange, is released when the exchange fails (severed source)
 * or when its TTL expires (abandonment) — proven by counting real closes
 * on the underlying response, beneath the deferred-close wrapper.
 */
@SpringBootTest(classes = DeferredCloseFailureTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.servlet.multipart.enabled=false",
            "restxop.timeouts.exchange-ttl=1s",
            "restxop.timeouts.read-wait=500ms",
        })
@Timeout(60)
// Thread.sleep here simulates pacing and park windows in real-time
// streaming behavior — replacing it with synchronization would change
// what is being tested
@SuppressWarnings("java:S2925")
class DeferredCloseFailureTest {

    static final String BOUNDARY = "trunc-boundary-01";
    static final String CONTENT_TYPE = "multipart/related; type=\"application/json\"; "
            + "boundary=\"" + BOUNDARY + "\"; start=\"<root>\"";

    static String rootPart() {
        return "\r\n--" + BOUNDARY + "\r\n"
                + "Content-ID: <root>\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Transfer-Encoding: binary\r\n\r\n"
                + "{\"title\":\"partial\",\"report\":{\"Include\":{\"href\":\"cid:att-1\"}}}";
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @RestController
    static class TestApp {

        /** Valid root, attachment part cut mid-content, no closing delimiter. */
        @GetMapping(value = "/truncated", produces = "multipart/related")
        ResponseEntity<byte[]> truncated() {
            String body = rootPart()
                    + "\r\n--" + BOUNDARY + "\r\n"
                    + "Content-ID: <att-1>\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n"
                    + "these bytes stop abru";
            return ResponseEntity.ok()
                    .header("Content-Type", CONTENT_TYPE)
                    .body(body.getBytes(StandardCharsets.ISO_8859_1));
        }

        /** Valid root, then the source stalls far past the exchange TTL. */
        @GetMapping(value = "/stalled", produces = "multipart/related")
        ResponseEntity<StreamingResponseBody> stalled() {
            return ResponseEntity.ok()
                    .header("Content-Type", CONTENT_TYPE)
                    .body(out -> {
                        out.write(rootPart().getBytes(StandardCharsets.ISO_8859_1));
                        out.write(("\r\n--" + BOUNDARY + "\r\n"
                                + "Content-ID: <att-1>\r\n"
                                + "Content-Type: application/octet-stream\r\n\r\npart")
                                .getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                        try {
                            Thread.sleep(8000); // far beyond the 1s TTL
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
        }
    }

    @Autowired
    private RestTemplateBuilder restTemplateBuilder;

    @LocalServerPort
    private int port;

    private final AtomicInteger realCloses = new AtomicInteger();

    private RestTemplate clientWithCloseProbe() {
        return restTemplateBuilder
                .requestFactory(() -> new CloseCountingFactory(
                        new SimpleClientHttpRequestFactory(), realCloses))
                .build();
    }

    @Test
    void severedSourceFailsTypedAndReleasesTheResponse() {
        RestTemplate restTemplate = clientWithCloseProbe();

        ReportPayload payload = restTemplate.getForObject(
                "http://localhost:" + port + "/truncated", ReportPayload.class);

        assertNotNull(payload);
        assertEquals("partial", payload.title, "payload usable before the failure");
        RestxopException error = assertThrows(RestxopException.class,
                () -> payload.report.contentStream().readAllBytes());
        assertInstanceOf(RestxopException.class, error);
        awaitRealClose("severed exchange must release the underlying response");
    }

    @Test
    void abandonedResponseIsReleasedWhenTheTtlExpires() {
        RestTemplate restTemplate = clientWithCloseProbe();

        ReportPayload payload = restTemplate.getForObject(
                "http://localhost:" + port + "/stalled", ReportPayload.class);

        assertNotNull(payload);
        assertEquals("partial", payload.title);
        // Abandon the attachment entirely: the TTL reaper must release the
        // held connection while the server is still stalling
        awaitRealClose("TTL reaper must release the abandoned response");
        RestxopException error = assertThrows(RestxopException.class,
                () -> payload.report.contentStream().readAllBytes());
        assertInstanceOf(RestxopException.class, error);
    }

    private void awaitRealClose(String message) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (realCloses.get() == 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(realCloses.get() > 0, message);
    }

    /** Counts closes on the real (innermost) client response. */
    static final class CloseCountingFactory implements ClientHttpRequestFactory {

        private final ClientHttpRequestFactory delegate;
        private final AtomicInteger closes;

        CloseCountingFactory(ClientHttpRequestFactory delegate, AtomicInteger closes) {
            this.delegate = delegate;
            this.closes = closes;
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
            ClientHttpRequest request = delegate.createRequest(uri, method);
            return new ClientHttpRequest() {
                @Override
                public java.util.Map<String, Object> getAttributes() {
                    return request.getAttributes();
                }

                @Override
                public ClientHttpResponse execute() throws IOException {
                    ClientHttpResponse response = request.execute();
                    return new org.springframework.http.client.ClientHttpResponse() {
                        @Override
                        public void close() {
                            closes.incrementAndGet();
                            response.close();
                        }

                        @Override
                        public java.io.InputStream getBody() throws IOException {
                            return response.getBody();
                        }

                        @Override
                        public org.springframework.http.HttpStatusCode getStatusCode()
                                throws IOException {
                            return response.getStatusCode();
                        }

                        @Override
                        public String getStatusText() throws IOException {
                            return response.getStatusText();
                        }

                        @Override
                        public org.springframework.http.HttpHeaders getHeaders() {
                            return response.getHeaders();
                        }
                    };
                }

                @Override
                public java.io.OutputStream getBody() throws IOException {
                    return request.getBody();
                }

                @Override
                public HttpMethod getMethod() {
                    return request.getMethod();
                }

                @Override
                public URI getURI() {
                    return request.getURI();
                }

                @Override
                public org.springframework.http.HttpHeaders getHeaders() {
                    return request.getHeaders();
                }
            };
        }
    }
}
