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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.restxop.Attachment;
import dev.restxop.testkit.model.ReportPayload;
import feign.Feign;
import feign.RequestLine;
import feign.codec.Decoder;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.openfeign.FeignBuilderCustomizer;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * US4 adoption surface on the Boot 3 generation: RestClient with deferred
 * close (FR-024), the OpenFeign decoder (FR-027), and the strict
 * multipart-resolver guard (research R6) — with the servlet multipart
 * machinery deliberately left ENABLED so attachment messages and form
 * uploads coexist on one server.
 */
@SpringBootTest(classes = Boot3AdoptionSurfaceTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
// Thread.sleep here simulates pacing and park windows in real-time
// streaming behavior — replacing it with synchronization would change
// what is being tested
@SuppressWarnings("java:S2925")
class Boot3AdoptionSurfaceTest {

    static final byte[] STREAMED_CONTENT = streamedContent();

    private static byte[] streamedContent() {
        byte[] data = new byte[96 * 1024];
        new Random(99).nextBytes(data);
        return data;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @RestController
    static class TestApp {

        @Autowired
        private RestxopRuntime runtime;

        /**
         * Streams root immediately, stalls, then streams the attachment —
         * a client without deferred close severs the message at extraction.
         */
        @GetMapping(value = "/streamed", produces = "multipart/related")
        void streamed(jakarta.servlet.http.HttpServletResponse response) throws IOException {
            var writer = runtime.newWriter();
            response.setHeader("Content-Type", writer.contentType());
            OutputStream out = response.getOutputStream();
            var exchange = runtime.openExchange();
            try {
                ReportPayload payload = new ReportPayload("streamed",
                        Attachment.builder(new SlowStream(STREAMED_CONTENT, 400))
                                .filename("slow.bin")
                                .contentLength(STREAMED_CONTENT.length)
                                .build());
                writer.write(exchange, payload, out);
                exchange.complete();
            } catch (IOException | RuntimeException e) {
                exchange.fail(e);
                throw e;
            }
        }

        @PostMapping(value = "/upload", consumes = "multipart/related",
                produces = "application/json")
        Map<String, Object> upload(@RequestBody ReportPayload payload) throws IOException {
            byte[] received = payload.report.contentStream().readAllBytes();
            return Map.of("title", payload.title, "size", received.length);
        }

        @PostMapping(value = "/form", consumes = "multipart/form-data",
                produces = "application/json")
        Map<String, Object> form(@RequestParam("file") MultipartFile file) throws IOException {
            return Map.of("name", String.valueOf(file.getOriginalFilename()),
                    "size", file.getBytes().length);
        }
    }

    /** Delays mid-stream so extraction returns long before the content ends. */
    static final class SlowStream extends java.io.InputStream {

        private final byte[] data;
        private final long delayMillis;
        private int position;
        private boolean delayed;

        SlowStream(byte[] data, long delayMillis) {
            this.data = data;
            this.delayMillis = delayMillis;
        }

        @Override
        public int read() {
            return position >= data.length ? -1 : data[position++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (position >= data.length) {
                return -1;
            }
            if (!delayed && position >= data.length / 2) {
                delayed = true;
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int n = Math.min(len, Math.min(8192, data.length - position));
            System.arraycopy(data, position, b, off, n);
            position += n;
            return n;
        }
    }

    interface StreamedApi {
        @RequestLine("GET /streamed")
        ReportPayload streamed();
    }

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    private RestTemplateBuilder restTemplateBuilder;

    @Autowired
    private Decoder feignDecoder;

    @Autowired
    private FeignBuilderCustomizer feignBuilderCustomizer;

    @LocalServerPort
    private int port;

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void restClientKeepsTheResponseOpenUntilTheDrainCompletes() throws IOException {
        RestClient restClient = restClientBuilder.build();

        ReportPayload payload = restClient.get()
                .uri(base() + "/streamed")
                .retrieve()
                .body(ReportPayload.class);

        assertNotNull(payload);
        assertEquals("streamed", payload.title);
        // The server is still stalling mid-attachment at this point, so the
        // read below only succeeds if the response was not closed after the
        // typed payload was returned (FR-024)
        byte[] received = payload.report.contentStream().readAllBytes();
        assertArrayEquals(STREAMED_CONTENT, received);
        assertEquals("slow.bin", payload.report.filename().orElseThrow());
    }

    @Test
    void restClientPostsAttachmentMessages() {
        byte[] content = new byte[300 * 1024];
        new Random(3).nextBytes(content);
        RestClient restClient = restClientBuilder.build();

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri(base() + "/upload")
                .body(new ReportPayload("via-restclient", Attachment.of(content)))
                .retrieve()
                .body(Map.class);

        assertNotNull(response);
        assertEquals("via-restclient", response.get("title"));
        assertEquals(content.length, response.get("size"));
    }

    @Test
    void feignDecoderStreamsWithDeferredClose() throws IOException {
        Feign.Builder builder = Feign.builder();
        feignBuilderCustomizer.customize(builder);
        StreamedApi api = builder.decoder(feignDecoder).target(StreamedApi.class, base());

        ReportPayload payload = api.streamed();

        assertNotNull(payload);
        assertEquals("streamed", payload.title);
        byte[] received = payload.report.contentStream().readAllBytes();
        assertArrayEquals(STREAMED_CONTENT, received);
    }

    @Test
    void attachmentMessagesAndFormUploadsCoexistWithMultipartEnabled() {
        var restTemplate = restTemplateBuilder.build();

        // multipart/related passes the strict resolver untouched (R6)
        byte[] content = "coexistence content".getBytes(StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> related = restTemplate.postForObject(base() + "/upload",
                new ReportPayload("coexist", Attachment.of(content)), Map.class);
        assertNotNull(related);
        assertEquals(content.length, related.get("size"));

        // multipart/form-data is still resolved into MultipartFile
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("form bytes".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "form.txt";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        @SuppressWarnings("unchecked")
        Map<String, Object> formResponse = restTemplate.postForObject(base() + "/form",
                new HttpEntity<>(form, headers), Map.class);
        assertNotNull(formResponse);
        assertEquals("form.txt", formResponse.get("name"));
        assertEquals("form bytes".length(), formResponse.get("size"));
    }
}
