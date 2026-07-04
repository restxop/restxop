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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.restxop.Attachment;
import dev.restxop.samples.server.SampleServerApplication;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestTemplate;

/**
 * SC-001 / SC-002 acceptance: a 1 GiB attachment round-trips in both
 * directions with the whole test JVM — hosting BOTH the sample server and
 * the client — capped at 256 MB (surefire argLine), checksums identical,
 * and the typed payload usable within 2 seconds of response start, long
 * before the transfer completes.
 *
 * <p>Size override for exploratory runs:
 * {@code -Drestxop.acceptance.bytes=...}.</p>
 */
@Tag("fidelity")
@SpringBootTest(classes = SampleServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.servlet.multipart.enabled=false",
            "spring.main.web-application-type=servlet",
        })
@Timeout(900)
class LargeTransferAcceptanceTest {

    private static final Logger log = LoggerFactory.getLogger(LargeTransferAcceptanceTest.class);

    private static final long SIZE = Long.getLong("restxop.acceptance.bytes", 1L << 30);
    private static final long SEED = 20260704;

    @Autowired
    private RestTemplateBuilder restTemplateBuilder;

    @LocalServerPort
    private int port;

    @Test
    void oneGigabyteRoundTripsBothWaysWithinBoundedMemory() throws Exception {
        assertTrue(Runtime.getRuntime().maxMemory() <= 300L * 1024 * 1024,
                "test must run with the 256 MB heap cap, was "
                        + Runtime.getRuntime().maxMemory());
        RestTemplate restTemplate = restTemplateBuilder.build();
        String base = "http://localhost:" + port;
        String expectedSha = SampleClientApplication.expectedSha(SEED, SIZE);

        // --- Download direction -----------------------------------------
        long start = System.nanoTime();
        DownloadPayload payload = restTemplate.getForObject(
                base + "/download?size=" + SIZE + "&seed=" + SEED, DownloadPayload.class);
        long payloadAtMillis = (System.nanoTime() - start) / 1_000_000;

        assertNotNull(payload);
        assertEquals(SIZE, payload.size, "typed fields readable immediately");
        assertTrue(payloadAtMillis < 2000,
                "payload must be available within 2s of response start (SC-002), took "
                        + payloadAtMillis + " ms");

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long copied = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new DigestInputStream(payload.data.contentStream(), digest)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                copied += n;
            }
        }
        long doneAtMillis = (System.nanoTime() - start) / 1_000_000;
        log.info("download: payload at {} ms, transfer complete at {} ms", payloadAtMillis,
                doneAtMillis);

        assertTrue(payloadAtMillis < doneAtMillis,
                "payload must arrive before the attachment transfer completes (SC-002)");
        assertEquals(SIZE, copied, "no bytes added or dropped (SC-003)");
        assertEquals(expectedSha, HexFormat.of().formatHex(digest.digest()),
                "downloaded content must be checksum-identical (SC-001)");

        // --- Upload direction --------------------------------------------
        UploadPayload upload = new UploadPayload("acceptance",
                Attachment.builder(new GeneratedInputStream(SEED, SIZE))
                        .filename("acceptance.bin")
                        .contentLength(SIZE)
                        .build());
        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                restTemplate.postForObject(base + "/upload", upload, Map.class);

        assertNotNull(response);
        assertEquals(SIZE, ((Number) response.get("size")).longValue());
        assertEquals(expectedSha, response.get("sha256"),
                "uploaded content must be checksum-identical (SC-001, request direction)");
    }
}
