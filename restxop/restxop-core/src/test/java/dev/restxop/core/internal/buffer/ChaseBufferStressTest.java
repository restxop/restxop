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
package dev.restxop.core.internal.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.restxop.spi.OverflowStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Concurrency stress: 10k iterations of randomized writer/reader pacing,
 * window sizes, and chunk sizes; every iteration asserts checksum equality
 * of written and read bytes. Runs in the scheduled `stress` group.
 */
@Tag("stress")
class ChaseBufferStressTest {

    private static final int ITERATIONS = 10_000;

    @Test
    @Timeout(1200)
    void randomizedPacingPreservesChecksumAcross10kIterations() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                runIteration(pool, i);
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private void runIteration(ExecutorService pool, int seed) throws Exception {
        Random random = new Random(seed);
        int windowSize = 512 + random.nextInt(4096);
        byte[] data = new byte[random.nextInt(48 * 1024)];
        random.nextBytes(data);

        InMemoryStore store = new InMemoryStore();
        ChaseBuffer buffer = new ChaseBuffer("stress-" + seed, "cid", windowSize,
                Long.MAX_VALUE, Duration.ofSeconds(20), () -> store, total -> {
                });

        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Future<?> writer = pool.submit(() -> {
            Random pacing = new Random(seed * 31L + 1);
            try {
                int off = 0;
                while (off < data.length) {
                    int n = Math.min(1 + pacing.nextInt(8 * 1024), data.length - off);
                    buffer.write(data, off, n);
                    off += n;
                    if (pacing.nextInt(10) == 0) {
                        Thread.onSpinWait();
                    }
                    if (pacing.nextInt(200) == 0) {
                        Thread.sleep(1);
                    }
                }
                buffer.completeWriter();
            } catch (Throwable t) {
                writerFailure.set(t);
                buffer.poison(t);
            }
        });

        Future<byte[]> reader = pool.submit(() -> {
            Random pacing = new Random(seed * 31L + 2);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[1 + pacing.nextInt(8 * 1024)];
            int n;
            while ((n = buffer.read(chunk, 0, chunk.length)) != -1) {
                out.write(chunk, 0, n);
                if (pacing.nextInt(10) == 0) {
                    Thread.onSpinWait();
                }
                if (pacing.nextInt(200) == 0) {
                    Thread.sleep(1);
                }
            }
            return out.toByteArray();
        });

        byte[] received = reader.get(60, TimeUnit.SECONDS);
        writer.get(60, TimeUnit.SECONDS);
        buffer.release();

        assertNull(writerFailure.get(), "iteration " + seed + " writer failed");
        assertEquals(data.length, received.length, "iteration " + seed + " length mismatch");
        assertArrayEquals(checksum(data), checksum(received), "iteration " + seed + " checksum mismatch");
    }

    private static byte[] checksum(byte[] data) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static final class InMemoryStore implements OverflowStore {

        private final ByteArrayOutputStream content = new ByteArrayOutputStream();

        @Override
        public void append(byte[] buf, int off, int len) {
            content.write(buf, off, len);
        }

        @Override
        public int read(long position, byte[] buf, int off, int len) {
            byte[] all = content.toByteArray();
            int n = Math.min(len, (int) (all.length - position));
            System.arraycopy(all, (int) position, buf, off, n);
            return n;
        }

        @Override
        public void close() throws IOException {
            content.reset();
        }
    }
}
