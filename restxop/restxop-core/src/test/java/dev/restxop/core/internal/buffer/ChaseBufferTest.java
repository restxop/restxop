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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.restxop.ExchangeFailedException;
import dev.restxop.ExchangeTimeoutException;
import dev.restxop.LimitExceededException;
import dev.restxop.spi.OverflowStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
// Thread.sleep here simulates pacing and park windows in real-time
// streaming behavior — replacing it with synchronization would change
// what is being tested
// (S8714/S1181: concurrent readers run in spawned threads, so throwables
// are captured cross-thread rather than via assertThrows)
@SuppressWarnings({"java:S2925", "java:S8714", "java:S1181"})
class ChaseBufferTest {

    private static final Duration READ_WAIT = Duration.ofSeconds(5);

    private RecordingStore store = new RecordingStore();
    private final AtomicLong lastSpillTotal = new AtomicLong(-1);
    private int overflowCreations;

    private ChaseBuffer buffer(int windowSize, long maxSpool, Duration readWait) {
        return new ChaseBuffer("ex-test", "cid-test", windowSize, maxSpool, readWait,
                () -> {
                    overflowCreations++;
                    return store;
                },
                lastSpillTotal::set);
    }

    private static byte[] sequence(int length) {
        byte[] data = new byte[length];
        new Random(42).nextBytes(data);
        return data;
    }

    private static byte[] readFully(ChaseBuffer buffer, int chunkSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[chunkSize];
        int n;
        while ((n = buffer.read(chunk, 0, chunk.length)) != -1) {
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }

    @Test
    void writeThenChaseDeliversBytesWithoutTouchingDisk() throws IOException {
        ChaseBuffer buffer = buffer(1024, Long.MAX_VALUE, READ_WAIT);
        byte[] data = sequence(600);

        buffer.write(data, 0, data.length);
        buffer.completeWriter();

        assertArrayEquals(data, readFully(buffer, 128));
        assertEquals(0, overflowCreations, "prompt consumer must never touch disk");
        assertEquals(-1, lastSpillTotal.get());
    }

    @Test
    void windowWraparoundPreservesByteOrder() throws IOException {
        ChaseBuffer buffer = buffer(16, Long.MAX_VALUE, READ_WAIT);
        byte[] data = sequence(64);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8];

        // Interleave writes and reads so the ring's head crosses the array
        // boundary several times without ever overflowing to disk
        int written = 0;
        while (written < data.length) {
            int n = Math.min(10, data.length - written);
            // Keep the window from overflowing: drain first if n would not fit
            while (buffer.availableInWindow() + n > 16) {
                int r = buffer.read(chunk, 0, chunk.length);
                out.write(chunk, 0, r);
            }
            buffer.write(data, written, n);
            written += n;
        }
        buffer.completeWriter();
        int r;
        while ((r = buffer.read(chunk, 0, chunk.length)) != -1) {
            out.write(chunk, 0, r);
        }

        assertArrayEquals(data, out.toByteArray());
        assertEquals(0, overflowCreations);
    }

    @Test
    void overflowSpillsToStoreAndDrainsBackInOrder() throws IOException {
        ChaseBuffer buffer = buffer(16, Long.MAX_VALUE, READ_WAIT);
        byte[] first = sequence(48); // 16 window + 32 spilled

        buffer.write(first, 0, first.length);
        assertEquals(1, overflowCreations);
        assertEquals(32, store.appendedBytes());
        assertEquals(32, lastSpillTotal.get(), "spill listener sees the running spool total");

        // Reader drains window then file, byte-exact
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8];
        int total = 0;
        while (total < first.length) {
            int n = buffer.read(chunk, 0, chunk.length);
            out.write(chunk, 0, n);
            total += n;
        }
        assertArrayEquals(first, out.toByteArray());

        // File fully drained: writer switches back to the memory window
        long appendsBefore = store.appendedBytes();
        byte[] second = sequence(8);
        buffer.write(second, 0, second.length);
        assertEquals(appendsBefore, store.appendedBytes(), "post-drain writes go to the window again");
        buffer.completeWriter();

        assertArrayEquals(second, readFully(buffer, 8));
        assertFalse(store.closed, "overflow store lives until release");
        buffer.release();
        assertTrue(store.closed, "release closes (and thereby deletes) the overflow store");
    }

    @Test
    void readerBlocksUntilWriterCatchesUp() throws Exception {
        ChaseBuffer buffer = buffer(1024, Long.MAX_VALUE, READ_WAIT);
        byte[] data = sequence(100);
        CountDownLatch readerBlocked = new CountDownLatch(1);
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            try {
                readerBlocked.countDown();
                received.set(readFully(buffer, 32));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        reader.start();
        assertTrue(readerBlocked.await(5, TimeUnit.SECONDS));
        Thread.sleep(100); // let the reader actually park on the condition

        buffer.write(data, 0, data.length);
        buffer.completeWriter();
        reader.join(5000);

        assertFalse(reader.isAlive());
        assertEquals(null, failure.get());
        assertArrayEquals(data, received.get());
    }

    @Test
    void poisonWakesBlockedReaderWithCause() throws Exception {
        ChaseBuffer buffer = buffer(1024, Long.MAX_VALUE, READ_WAIT);
        CountDownLatch readerStarted = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            byte[] chunk = new byte[16];
            try {
                readerStarted.countDown();
                buffer.read(chunk, 0, chunk.length);
                fail("read must not succeed on a poisoned buffer");
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        reader.start();
        assertTrue(readerStarted.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);

        IOException cause = new IOException("transport severed");
        long before = System.nanoTime();
        buffer.poison(cause);
        reader.join(5000);
        long waitedMillis = (System.nanoTime() - before) / 1_000_000;

        assertFalse(reader.isAlive(), "poison must wake the already-blocked reader");
        assertTrue(waitedMillis < 2000, "wake-up must be prompt, took " + waitedMillis + "ms");
        ExchangeFailedException failed = assertInstanceOf(ExchangeFailedException.class, thrown.get());
        assertEquals(cause, failed.getCause());

        // Subsequent reads keep throwing the causal error
        assertThrows(ExchangeFailedException.class, () -> buffer.read(new byte[4], 0, 4));
    }

    @Test
    void readWaitDeadlineExpiresWithTimeoutError() {
        ChaseBuffer buffer = buffer(1024, Long.MAX_VALUE, Duration.ofMillis(200));
        long before = System.nanoTime();
        assertThrows(ExchangeTimeoutException.class, () -> buffer.read(new byte[16], 0, 16));
        long waitedMillis = (System.nanoTime() - before) / 1_000_000;
        assertTrue(waitedMillis >= 180, "must wait out the deadline, waited " + waitedMillis + "ms");
        assertTrue(waitedMillis < 2000, "must not wait far past the deadline, waited " + waitedMillis + "ms");
    }

    @Test
    void awaitWriterConditionGivesUpAtTheDeadline() {
        ChaseBuffer buffer = buffer(1024, Long.MAX_VALUE, Duration.ofMillis(200));
        long before = System.nanoTime();
        assertFalse(buffer.awaitWriterCondition(() -> false));
        long waitedMillis = (System.nanoTime() - before) / 1_000_000;
        assertTrue(waitedMillis >= 180, "must wait out the deadline, waited " + waitedMillis + "ms");
        assertTrue(waitedMillis < 2000, "must not wait far past the deadline, waited " + waitedMillis + "ms");
    }

    @Test
    void awaitWriterConditionWakesOnWriterProgress() throws Exception {
        ChaseBuffer buffer = buffer(1024, Long.MAX_VALUE, READ_WAIT);
        AtomicBoolean bound = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(100);
                bound.set(true);
                buffer.write(new byte[] {1}, 0, 1); // signals the waiter
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();
        assertTrue(buffer.awaitWriterCondition(bound::get),
                "waiter must observe the condition set before the writer's signal");
        writer.join();
    }

    @Test
    void awaitWriterConditionReturnsImmediatelyOnTerminalBuffer() {
        ChaseBuffer buffer = buffer(1024, Long.MAX_VALUE, READ_WAIT);
        buffer.completeWriter();
        long before = System.nanoTime();
        assertFalse(buffer.awaitWriterCondition(() -> false));
        assertTrue((System.nanoTime() - before) / 1_000_000 < 1000,
                "terminal buffers must not park the waiter");
    }

    @Test
    void allOverflowTrafficIsBulk() throws IOException {
        ChaseBuffer buffer = buffer(1024, Long.MAX_VALUE, READ_WAIT);
        byte[] data = sequence(64 * 1024);

        for (int off = 0; off < data.length; off += 8192) {
            buffer.write(data, off, Math.min(8192, data.length - off));
        }
        buffer.completeWriter();
        assertArrayEquals(data, readFully(buffer, 4096));

        assertTrue(store.appendedBytes() > 0, "test must actually exercise the overflow path");
        for (int size : store.appendSizes) {
            assertTrue(size > 1024, "store appends must be bulk, saw " + size);
        }
        for (int size : store.readSizes) {
            assertTrue(size > 1, "store reads must be bulk arrays, saw " + size);
        }
    }

    @Test
    void perAttachmentSpoolCapBreachFailsTheWrite() {
        ChaseBuffer buffer = buffer(16, 64, READ_WAIT);
        byte[] data = sequence(200); // would need 184 spooled > cap 64

        LimitExceededException e = assertThrows(LimitExceededException.class,
                () -> buffer.write(data, 0, data.length));
        assertEquals("spool.max-per-attachment", e.limitName());
        assertEquals(64, e.configuredValue());
    }

    @Test
    void interruptedReaderFailsPromptly() throws Exception {
        ChaseBuffer buffer = buffer(1024, Long.MAX_VALUE, READ_WAIT);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            try {
                buffer.read(new byte[16], 0, 16);
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        reader.start();
        Thread.sleep(150);
        reader.interrupt();

        assertTrue(done.await(5, TimeUnit.SECONDS), "interrupted read must return promptly");
        assertInstanceOf(dev.restxop.RestxopException.class, thrown.get());
    }

    /** In-memory OverflowStore recording call granularity for bulk-IO assertions. */
    static final class RecordingStore implements OverflowStore {

        private final ByteArrayOutputStream content = new ByteArrayOutputStream();
        final List<Integer> appendSizes = new ArrayList<>();
        final List<Integer> readSizes = new ArrayList<>();
        boolean closed;

        @Override
        public synchronized void append(byte[] buf, int off, int len) {
            appendSizes.add(len);
            content.write(buf, off, len);
        }

        @Override
        public synchronized int read(long position, byte[] buf, int off, int len) {
            readSizes.add(len);
            byte[] all = content.toByteArray();
            int available = (int) (all.length - position);
            int n = Math.min(len, available);
            System.arraycopy(all, (int) position, buf, off, n);
            return n;
        }

        long appendedBytes() {
            return appendSizes.stream().mapToLong(Integer::intValue).sum();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
