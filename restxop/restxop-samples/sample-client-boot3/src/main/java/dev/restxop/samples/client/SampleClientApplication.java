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
package dev.restxop.samples.client;

import dev.restxop.Attachment;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestTemplate;

/**
 * Sample restxop client (Boot 3 generation): downloads a generated
 * attachment, proves the typed payload was usable before the transfer
 * finished, copies the stream to disk while hashing it, verifies the
 * checksum against the regenerated expectation, and uploads the file back.
 *
 * <p>Size knob: {@code --restxop.sample.size=1GB} (quickstart §2).</p>
 */
@SpringBootApplication
public class SampleClientApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleClientApplication.class);

    @Value("${restxop.sample.url:http://localhost:8080}")
    private String serverUrl;

    @Value("${restxop.sample.size:64MB}")
    private DataSize size;

    @Value("${restxop.sample.seed:42}")
    private long seed;

    private final RestTemplateBuilder restTemplateBuilder;

    public SampleClientApplication(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplateBuilder = restTemplateBuilder;
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(SampleClientApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }

    @Override
    public void run(String... args) throws Exception {
        RestTemplate restTemplate = restTemplateBuilder.build();
        long bytes = size.toBytes();
        log.info("downloading {} bytes (seed {}) from {}", bytes, seed, serverUrl);

        long start = System.nanoTime();
        DownloadPayload payload = restTemplate.getForObject(
                serverUrl + "/download?size=" + bytes + "&seed=" + seed, DownloadPayload.class);
        long payloadAtMillis = elapsedMillis(start);
        log.info("payload available after {} ms — name='{}', size={}, seed={} "
                + "(attachment still streaming)", payloadAtMillis, payload.name, payload.size,
                payload.seed);

        // Private 0700 directory: nothing else on the host can observe or
        // replace the download while it is being written
        Path target = Files.createTempDirectory("restxop-sample-").resolve("download.bin");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long copied;
        try (InputStream in = new DigestInputStream(payload.data.contentStream(), digest);
                OutputStream out = Files.newOutputStream(target)) {
            copied = in.transferTo(out);
        }
        long doneAtMillis = elapsedMillis(start);
        String receivedSha = HexFormat.of().formatHex(digest.digest());
        log.info("attachment copied to {} after {} ms: {} bytes, sha256(received) = {}",
                target, doneAtMillis, copied, receivedSha);

        String expectedSha = expectedSha(seed, bytes);
        log.info("sha256(expected) = {} — match: {}", expectedSha, expectedSha.equals(receivedSha));

        log.info("uploading the file back to {}/upload", serverUrl);
        UploadPayload upload = new UploadPayload("sample-roundtrip", Attachment.of(target));
        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                restTemplate.postForObject(serverUrl + "/upload", upload, Map.class);
        log.info("server received upload: {} — sha match: {}", response,
                receivedSha.equals(response.get("sha256")));

        Files.deleteIfExists(target);
        log.info("done: round trip of {} bytes complete", copied);
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Regenerates the deterministic content and hashes it. */
    static String expectedSha(long seed, long size) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (GeneratedInputStream generated = new GeneratedInputStream(seed, size)) {
            int n;
            while ((n = generated.read(buffer, 0, buffer.length)) != -1) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
