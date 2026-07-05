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
package dev.restxop.samples.feign;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Declarative (OpenFeign) restxop client: the typed payload arrives
 * immediately, the attachment streams behind it, and the connection is
 * released by the library when the drain completes — no plumbing in
 * application code. Feign request bodies are buffered by Feign itself, so
 * use RestTemplate/RestClient for large uploads.
 */
@SpringBootApplication
@EnableFeignClients
public class SampleFeignClientApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleFeignClientApplication.class);

    /** Declarative download API; the restxop decoder handles the response. */
    @FeignClient(name = "restxop-sample", url = "${restxop.sample.url:http://localhost:8080}")
    public interface SampleDownloadClient {

        @GetMapping(value = "/download", produces = "multipart/related")
        DownloadPayload download(@RequestParam("size") long size, @RequestParam("seed") long seed);
    }

    @Value("${restxop.sample.size:64MB}")
    private DataSize size;

    @Value("${restxop.sample.seed:42}")
    private long seed;

    private final SampleDownloadClient client;

    public SampleFeignClientApplication(SampleDownloadClient client) {
        this.client = client;
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(SampleFeignClientApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }

    @Override
    public void run(String... args) throws Exception {
        long bytes = size.toBytes();
        long start = System.nanoTime();
        DownloadPayload payload = client.download(bytes, seed);
        log.info("payload available after {} ms — name='{}', size={} (attachment still streaming)",
                (System.nanoTime() - start) / 1_000_000, payload.name, payload.size);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long copied = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new DigestInputStream(payload.data.contentStream(), digest)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                copied += n;
            }
        }
        String received = HexFormat.of().formatHex(digest.digest());
        String expected = expectedSha(seed, bytes);
        log.info("streamed {} bytes in {} ms — sha match: {}", copied,
                (System.nanoTime() - start) / 1_000_000, expected.equals(received));
    }

    static String expectedSha(long seed, long size) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        GeneratedInputStream generated = new GeneratedInputStream(seed, size);
        int n;
        while ((n = generated.read(buffer, 0, buffer.length)) != -1) {
            digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
