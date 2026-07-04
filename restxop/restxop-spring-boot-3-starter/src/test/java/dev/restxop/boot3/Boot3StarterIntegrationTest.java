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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.restxop.Attachment;
import dev.restxop.testkit.model.ReportPayload;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * End-to-end starter proof on the Boot 3 generation: auto-configured MVC
 * converter on the server, auto-configured RestTemplate customizer (incl.
 * deferred close) on the client, both directions over a real socket.
 */
@SpringBootTest(classes = Boot3StarterIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.servlet.multipart.enabled=false",
            "restxop.memory-window-per-part=64KB",
        })
@Timeout(120)
class Boot3StarterIntegrationTest {

    static final byte[] SERVED_CONTENT = servedContent();

    private static byte[] servedContent() {
        byte[] data = new byte[512 * 1024]; // several 64KB windows worth
        new Random(4711).nextBytes(data);
        return data;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @RestController
    static class TestApp {

        @GetMapping(value = "/report", produces = "multipart/related")
        ReportPayload report() {
            return new ReportPayload("integration report",
                    Attachment.builder(SERVED_CONTENT)
                            .filename("data.bin")
                            .contentType("application/octet-stream")
                            .build());
        }

        @PostMapping(value = "/upload", consumes = "multipart/related", produces = "application/json")
        Map<String, Object> upload(@RequestBody ReportPayload payload) throws IOException {
            byte[] received = payload.report.contentStream().readAllBytes();
            return Map.of(
                    "title", payload.title,
                    "size", received.length,
                    "sha256", sha256Hex(received));
        }
    }

    @Autowired
    private RestTemplateBuilder restTemplateBuilder;

    @LocalServerPort
    private int port;

    @Autowired
    private RestxopRuntime runtime;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void serverToClientRoundTripIsByteExact() throws IOException {
        RestTemplate restTemplate = restTemplateBuilder.build();

        ReportPayload payload = restTemplate.getForObject(url("/report"), ReportPayload.class);

        assertNotNull(payload);
        assertEquals("integration report", payload.title);
        byte[] received = payload.report.contentStream().readAllBytes();
        assertArrayEquals(SERVED_CONTENT, received);
        assertEquals("data.bin", payload.report.filename().orElseThrow(),
                "part-header metadata is exposed once the part has arrived (FR-019)");
    }

    @Test
    void clientToServerRoundTripIsByteExact() throws NoSuchAlgorithmException {
        RestTemplate restTemplate = restTemplateBuilder.build();
        byte[] content = new byte[300 * 1024];
        new Random(7).nextBytes(content);
        ReportPayload outgoing = new ReportPayload("upload",
                Attachment.builder(content).filename("up.bin").build());

        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                restTemplate.postForObject(url("/upload"), outgoing, Map.class);

        assertNotNull(response);
        assertEquals("upload", response.get("title"));
        assertEquals(content.length, response.get("size"));
        assertEquals(sha256Hex(content), response.get("sha256"));
    }

    @Test
    void propertiesBindIntoTheCoreConfig() {
        assertEquals(64 * 1024, runtime.config().memoryWindowPerPart(),
                "restxop.* properties must bind (FR-029)");
        assertTrue(runtime.supportsMediaType("multipart/related"));
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
