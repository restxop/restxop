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

import dev.restxop.Attachment;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The feature-002 showcase endpoint: one request returns a document's
 * metadata fields AND its (synthesized, deterministic) PDF content as a
 * streamed attachment. CORS is enabled so browser front ends on another
 * origin (the demo dev server) can consume it directly.
 */
@RestController
@CrossOrigin
public class DocumentController {

    /** Typed document payload: metadata plus the streamed PDF. */
    public static class DocumentPayload {

        public String title;
        public String author;
        public int pages;
        public Instant created;
        public String status;
        public List<String> tags;
        public long sizeBytes;
        public Attachment data;

        public DocumentPayload() {
        }
    }

    @GetMapping(value = "/document", produces = "multipart/related")
    public DocumentPayload document(@RequestParam(defaultValue = "8388608") long size) {
        byte[] pdf = PdfSynthesizer.synthesize(size, "restxop streaming demo");
        DocumentPayload payload = new DocumentPayload();
        payload.title = "Quarterly Compliance Report";
        payload.author = "restxop sample server";
        payload.pages = 1;
        payload.created = Instant.parse("2026-07-05T12:00:00Z");
        payload.status = "FINAL";
        payload.tags = List.of("demo", "streaming", "multipart-related");
        payload.sizeBytes = pdf.length;
        payload.data = Attachment.builder(pdf)
                .filename("quarterly-report.pdf")
                .contentType("application/pdf")
                .build();
        return payload;
    }
}
