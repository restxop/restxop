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
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Attachment endpoints: everything is plain {@code @RequestBody}/return values. */
@RestController
@CrossOrigin
public class SampleController {

    private static final Logger log = LoggerFactory.getLogger(SampleController.class);

    @GetMapping(value = "/download", produces = "multipart/related")
    public DownloadPayload download(
            @RequestParam(defaultValue = "67108864") long size,
            @RequestParam(defaultValue = "42") long seed) {
        log.info("serving generated attachment: {} bytes, seed {}", size, seed);
        Attachment data = Attachment.builder(new GeneratedInputStream(seed, size))
                .filename("sample-" + size + ".bin")
                .contentType("application/octet-stream")
                .contentLength(size)
                .build();
        return new DownloadPayload("generated-sample", seed, size, data);
    }

    @PostMapping(value = "/upload", consumes = "multipart/related", produces = "application/json")
    public Map<String, Object> upload(@RequestBody UploadPayload payload) throws IOException {
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = payload.data.contentStream()) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
                total += n;
            }
        }
        String sha256 = HexFormat.of().formatHex(digest.digest());
        log.info("received upload '{}': {} bytes, sha256 {}", payload.label, total, sha256);
        return Map.of("label", payload.label, "size", total, "sha256", sha256);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
