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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.restxop.Attachment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

/**
 * SC-009 load scenario (tag {@code load}): 100 concurrent mixed exchanges
 * — half downloads, half uploads — with attachment sizes far beyond the
 * 64 KB memory window so spooling triggers, verified per exchange by
 * checksum against its own seed (any cross-exchange interference shows up
 * as a checksum mismatch). Zero failures, zero timeout violations, zero
 * residual spool files.
 */
@Tag("load")
@SpringBootTest(classes = SampleServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.servlet.multipart.enabled=false",
            "restxop.memory-window-per-part=64KB",
        })
@Timeout(600)
class LoadScenarioTest {

    private static final Logger log = LoggerFactory.getLogger(LoadScenarioTest.class);

    private static final int EXCHANGES = 100;
    private static final Path SPOOL_DIR = initSpoolDir();

    private static Path initSpoolDir() {
        try {
            return Files.createTempDirectory("restxop-load-spool-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void spoolDirectory(DynamicPropertyRegistry registry) {
        registry.add("restxop.spool.directory", SPOOL_DIR::toString);
    }

    @Autowired
    private RestTemplateBuilder restTemplateBuilder;

    @LocalServerPort
    private int port;

    @Test
    void oneHundredConcurrentMixedExchangesCompleteCleanly() throws Exception {
        RestTemplate restTemplate = restTemplateBuilder.build();
        String base = "http://localhost:" + port;
        ExecutorService pool = Executors.newFixedThreadPool(EXCHANGES);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(EXCHANGES);
        List<String> failures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < EXCHANGES; i++) {
            final int id = i;
            pool.submit(() -> {
                try {
                    start.await();
                    long seed = 1000L + id;
                    long size = 512 * 1024 + (id % 7) * 256 * 1024; // 0.5–2 MB
                    if (id % 2 == 0) {
                        download(restTemplate, base, id, seed, size);
                    } else {
                        upload(restTemplate, base, id, seed, size);
                    }
                } catch (Throwable t) {
                    failures.add("exchange " + id + ": " + t);
                } finally {
                    done.countDown();
                }
            });
        }

        long begin = System.nanoTime();
        start.countDown();
        assertTrue(done.await(300, TimeUnit.SECONDS), "all exchanges must finish in time");
        pool.shutdownNow();
        long elapsed = (System.nanoTime() - begin) / 1_000_000;
        log.info("{} concurrent exchanges completed in {} ms", EXCHANGES, elapsed);

        assertEquals(List.of(), failures, "zero failures, zero timeout violations, zero cross-talk");
        awaitEmptySpoolDir();
    }

    private void download(RestTemplate restTemplate, String base, int id, long seed, long size)
            throws Exception {
        DownloadPayload payload = restTemplate.getForObject(
                base + "/download?size=" + size + "&seed=" + seed, DownloadPayload.class);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        byte[] buffer = new byte[32 * 1024];
        try (InputStream in = new DigestInputStream(payload.data.contentStream(), digest)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total % (256 * 1024) < buffer.length) {
                    Thread.sleep(1); // mild consumer lag: exercises the spool path
                }
            }
        }
        if (total != size) {
            throw new AssertionError("exchange " + id + " size mismatch: " + total);
        }
        String received = HexFormat.of().formatHex(digest.digest());
        String expected = expectedSha(seed, size);
        if (!expected.equals(received)) {
            throw new AssertionError("exchange " + id + " checksum mismatch (cross-talk?)");
        }
    }

    private void upload(RestTemplate restTemplate, String base, int id, long seed, long size)
            throws Exception {
        UploadPayload payload = new UploadPayload("load-" + id,
                Attachment.builder(new GeneratedInputStream(seed, size))
                        .filename("load-" + id + ".bin")
                        .contentLength(size)
                        .build());
        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                restTemplate.postForObject(base + "/upload", payload, Map.class);
        if (response == null || !expectedSha(seed, size).equals(response.get("sha256"))) {
            throw new AssertionError("exchange " + id + " upload checksum mismatch");
        }
        if (((Number) response.get("size")).longValue() != size) {
            throw new AssertionError("exchange " + id + " upload size mismatch");
        }
    }

    private static String expectedSha(long seed, long size) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        GeneratedInputStream generated = new GeneratedInputStream(seed, size);
        int n;
        while ((n = generated.read(buffer, 0, buffer.length)) != -1) {
            digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void awaitEmptySpoolDir() throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        long count;
        do {
            try (var files = Files.list(SPOOL_DIR)) {
                count = files.count();
            }
            if (count == 0) {
                return;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        assertEquals(0, count, "zero residual spool files after the load run");
    }
}
