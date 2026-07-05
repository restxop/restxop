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
import dev.restxop.testkit.model.ReportPayload;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * The orchestrator-relay scenario (research R1's motivating case): a
 * middleman service calls the source document service with the restxop
 * client and places the received exchange-backed {@code Attachment} —
 * still streaming in from upstream — directly into its own outgoing
 * payload. The whole chain pipelines: the ultimate client sees the typed
 * payload and the first content bytes while the SOURCE is still producing,
 * and no hop ever holds the attachment in full.
 */
@SpringBootTest(classes = Boot4RelayIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.servlet.multipart.enabled=false",
            "restxop.memory-window-per-part=64KB",
        })
@Timeout(120)
class Boot4RelayIntegrationTest {

    static final int CONTENT_SIZE = 4 * 1024 * 1024;
    static final long SEED = 424242;

    /** Released by the ultimate client once it has verified early delivery. */
    static final CountDownLatch SOURCE_GATE = new CountDownLatch(1);
    static final AtomicBoolean SOURCE_FINISHED = new AtomicBoolean();
    static final AtomicLong SOURCE_BYTES_SERVED = new AtomicLong();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @RestController
    static class TestApp {

        @Autowired
        private RestTemplateBuilder restTemplateBuilder;

        @Autowired
        private Environment environment;

        /** The source document service: gated halfway so it is provably still producing. */
        @GetMapping(value = "/source", produces = "multipart/related")
        ReportPayload source() {
            InputStream generated = new GatedGeneratedStream(SEED, CONTENT_SIZE, CONTENT_SIZE / 2);
            return new ReportPayload("from-source",
                    Attachment.builder(generated)
                            .filename("relayed.bin")
                            .contentType("application/octet-stream")
                            .contentLength(CONTENT_SIZE)
                            .build());
        }

        /**
         * The middleman: fetches from the source and re-serves the SAME
         * exchange-backed attachment instance in a new payload — the
         * outgoing writer chases the incoming drain, hop to hop.
         */
        @GetMapping(value = "/relay", produces = "multipart/related")
        ReportPayload relay() {
            RestTemplate restTemplate = restTemplateBuilder.build();
            String port = environment.getProperty("local.server.port");
            ReportPayload upstream = restTemplate.getForObject(
                    "http://localhost:" + port + "/source", ReportPayload.class);
            assertNotNull(upstream);
            return new ReportPayload("via-relay", upstream.report);
        }
    }

    @Autowired
    private RestTemplateBuilder restTemplateBuilder;

    @LocalServerPort
    private int port;

    @Test
    void relayPipelinesTheAttachmentWithoutStoreAndForward() throws Exception {
        RestTemplate restTemplate = restTemplateBuilder.build();

        ReportPayload payload = restTemplate.getForObject(
                "http://localhost:" + port + "/relay", ReportPayload.class);

        // The typed payload crossed BOTH hops while the source is still
        // gated at the halfway mark — nothing was stored and forwarded
        assertNotNull(payload);
        assertEquals("via-relay", payload.title);
        assertTrue(SOURCE_BYTES_SERVED.get() < CONTENT_SIZE,
                "source must still be producing when the ultimate client has the payload");

        // First content bytes also flow through the gated source
        InputStream stream = payload.report.contentStream();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        DigestInputStream in = new DigestInputStream(stream, digest);
        byte[] head = in.readNBytes(256 * 1024);
        assertEquals(256 * 1024, head.length,
                "content must flow end to end before the source finishes");
        assertTrue(!SOURCE_FINISHED.get(),
                "the relay chain must deliver bytes while the source is mid-stream");

        // Release the source and finish; verify byte-exactness across two hops
        SOURCE_GATE.countDown();
        long total = head.length;
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
        }
        assertEquals(CONTENT_SIZE, total);
        assertArrayEquals(expectedSha(), digest.digest(),
                "checksum must survive source → relay → client");
        assertEquals("relayed.bin", payload.report.filename().orElseThrow(),
                "metadata must survive the relay hop");
        assertTrue(SOURCE_FINISHED.get());
    }

    private static byte[] expectedSha() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        java.util.SplittableRandom random = new java.util.SplittableRandom(SEED);
        byte[] buffer = new byte[8192];
        long remaining = CONTENT_SIZE;
        while (remaining > 0) {
            int chunk = (int) Math.min(buffer.length, remaining);
            fill(random, buffer, chunk);
            digest.update(buffer, 0, chunk);
            remaining -= chunk;
        }
        return digest.digest();
    }

    private static void fill(java.util.SplittableRandom random, byte[] buffer, int len) {
        for (int i = 0; i < len; i += 8) {
            long value = random.nextLong();
            for (int j = i; j < Math.min(i + 8, len); j++) {
                buffer[j] = (byte) value;
                value >>>= 8;
            }
        }
    }

    /** Deterministic content that blocks at the gate until released. */
    static final class GatedGeneratedStream extends InputStream {

        private final java.util.SplittableRandom random;
        private final long gateAt;
        private long produced;
        private long remaining;

        GatedGeneratedStream(long seed, long size, long gateAt) {
            this.random = new java.util.SplittableRandom(seed);
            this.remaining = size;
            this.gateAt = gateAt;
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int off, int len) {
            if (remaining <= 0) {
                SOURCE_FINISHED.set(true);
                return -1;
            }
            if (produced >= gateAt) {
                try {
                    if (!SOURCE_GATE.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("gate never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            long bound = SOURCE_GATE.getCount() > 0 ? gateAt - produced : remaining;
            int n = (int) Math.min(len, Math.max(1, bound));
            byte[] scratch = new byte[n];
            fill(random, scratch, n);
            System.arraycopy(scratch, 0, buffer, off, n);
            produced += n;
            remaining -= n;
            SOURCE_BYTES_SERVED.addAndGet(n);
            if (remaining <= 0) {
                SOURCE_FINISHED.set(true);
            }
            return n;
        }
    }
}
