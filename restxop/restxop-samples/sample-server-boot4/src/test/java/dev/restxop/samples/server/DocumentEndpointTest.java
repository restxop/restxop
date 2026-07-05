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
package dev.restxop.samples.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import java.net.URI;

/** The feature-002 showcase endpoint: metadata + a genuine PDF, CORS-enabled. */
@SpringBootTest(classes = SampleServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.servlet.multipart.enabled=false")
@Timeout(60)
class DocumentEndpointTest {

    @Autowired
    private RestTemplateBuilder restTemplateBuilder;

    @LocalServerPort
    private int port;

    @Test
    void servesMetadataPlusSynthesizedPdfWithCors() throws IOException {
        var restTemplate = restTemplateBuilder.build();
        var request = RequestEntity
                .method(HttpMethod.GET, URI.create("http://localhost:" + port + "/document?size=262144"))
                .header("Origin", "http://localhost:5173")
                .build();

        ResponseEntity<DocumentController.DocumentPayload> response =
                restTemplate.exchange(request, DocumentController.DocumentPayload.class);

        assertEquals("*", response.getHeaders().getAccessControlAllowOrigin(),
                "browser clients on another origin must be allowed (FR-014)");
        DocumentController.DocumentPayload payload = response.getBody();
        assertNotNull(payload);
        assertEquals("Quarterly Compliance Report", payload.title);
        assertEquals(List_of_tags(), payload.tags);

        byte[] pdf = payload.data.contentStream().readAllBytes();
        assertEquals(262144, pdf.length, "synthesized PDF hits the requested size exactly");
        String head = new String(pdf, 0, 8, StandardCharsets.ISO_8859_1);
        assertTrue(head.startsWith("%PDF-1."), "must be a genuine PDF header");
        String tail = new String(pdf, pdf.length - 32, 32, StandardCharsets.ISO_8859_1);
        assertTrue(tail.contains("%%EOF"), "must carry a valid PDF trailer");
        assertEquals("quarterly-report.pdf", payload.data.filename().orElseThrow());
    }

    private static java.util.List<String> List_of_tags() {
        return java.util.List.of("demo", "streaming", "multipart-related");
    }
}
