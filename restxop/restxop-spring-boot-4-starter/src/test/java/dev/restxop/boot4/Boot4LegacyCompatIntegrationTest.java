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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.restxop.Attachment;
import dev.restxop.testkit.model.LegacyPayload;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US5 through the starter (tag legacy): with compat enabled, the server
 * produces composite/related responses carrying the Response-ID header and
 * the client consumes them byte-exactly through the same converter stack.
 */
@Tag("legacy")
@SpringBootTest(classes = Boot4LegacyCompatIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.servlet.multipart.enabled=false",
            "restxop.legacy-compat.enabled=true",
        })
@Timeout(60)
class Boot4LegacyCompatIntegrationTest {

    static final byte[] CONTENT = "legacy mode content".getBytes(StandardCharsets.ISO_8859_1);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @RestController
    static class TestApp {

        @GetMapping(value = "/legacy-report", produces = "composite/related")
        LegacyPayload legacyReport() {
            return new LegacyPayload("String Value",
                    Attachment.builder(CONTENT).filename("Test-123").build());
        }
    }

    @Autowired
    private RestTemplateBuilder restTemplateBuilder;

    @LocalServerPort
    private int port;

    @Test
    void compatModeRoundTripsCompositeRelatedWithResponseId() throws Exception {
        var restTemplate = restTemplateBuilder.build();

        ResponseEntity<LegacyPayload> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/legacy-report", LegacyPayload.class);

        assertTrue(String.valueOf(response.getHeaders().getContentType())
                .startsWith("composite/related"), "legacy media type on the wire");
        assertNotNull(response.getHeaders().getFirst("Response-ID"),
                "legacy Response-ID header (wire-format §7)");
        LegacyPayload payload = response.getBody();
        assertNotNull(payload);
        assertEquals("String Value", payload.field1);
        assertArrayEquals(CONTENT, payload.resource1.contentStream().readAllBytes());
        assertEquals("Test-123", payload.resource1.filename().orElseThrow());
    }
}
