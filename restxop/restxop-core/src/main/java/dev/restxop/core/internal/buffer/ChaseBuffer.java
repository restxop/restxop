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

import dev.restxop.ExchangeFailedException;
import dev.restxop.ExchangeTimeoutException;
import dev.restxop.LimitExceededException;
import dev.restxop.RestxopException;
import dev.restxop.spi.OverflowStore;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded transfer buffer for one attachment part: a single writer (the
 * drain) fills an in-memory ring window at wire speed and never blocks on
 * the reader — when the window is full, subsequent bytes spill to an
 * {@link OverflowStore}. A single reader chases the writer memory-to-memory
 * while it keeps pace and drains window → overflow → window transparently,
 * in byte order, when it lags.
 *
 * <p>All signaling is lock/condition based. Every reader await carries the
 * read-wait deadline; writer completion, poison, and discard all signal the
 * condition so an already-blocked reader wakes immediately. Bulk array
 * transfers only — no per-byte paths exist.</p>
 */
public final class ChaseBuffer {

    private static final Logger log = LoggerFactory.getLogger(ChaseBuffer.class);

    /** Lazily creates the overflow store on first spill. */
    @FunctionalInterface
    public interface OverflowSupplier {
        OverflowStore create() throws IOException;
    }

    /**
     * Notified with the part's running spool total after each spill; the
     * exchange uses this for {@code bytesSpooled} events and may throw
     * {@link LimitExceededException} to enforce the per-message aggregate cap.
     */
    @FunctionalInterface
    public interface SpillListener {
        void bytesSpooled(long totalSpooledForPart);
    }

    private final String exchangeId;
    private final String contentId;
    private final long maxSpoolPerAttachment;
    private final long readWaitNanos;
    private final OverflowSupplier overflowSupplier;
    private final SpillListener spillListener;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition dataAvailable = lock.newCondition();

    private byte[] window;
    private int head;
    private int count;

    private OverflowStore overflow;
    private long fileWritten;
    private long fileRead;

    private long bytesWritten;
    private long bytesSpooled;

    private boolean writerComplete;
    private boolean discard;
    private boolean released;
    private Throwable poison;

    public ChaseBuffer(String exchangeId, String contentId, int windowSize,
            long maxSpoolPerAttachment, Duration readWait,
            OverflowSupplier overflowSupplier, SpillListener spillListener) {
        this.exchangeId = exchangeId;
        this.contentId = contentId;
        this.window = new byte[windowSize];
        this.maxSpoolPerAttachment = maxSpoolPerAttachment;
        this.readWaitNanos = readWait.toNanos();
        this.overflowSupplier = overflowSupplier;
        this.spillListener = spillListener;
    }

    /**
     * Appends {@code len} bytes from the drain. Never blocks on the reader:
     * bytes beyond the memory window spill to the overflow store, subject to
     * the per-attachment spool cap.
     */
    public void write(byte[] buf, int off, int len) throws IOException {
        lock.lock();
        try {
            if (poison != null) {
                throw asRestxopException(poison);
            }
            if (released) {
                throw new IllegalStateException("buffer already released");
            }
            if (writerComplete) {
                throw new IllegalStateException("writer already completed");
            }
            bytesWritten += len;
            if (discard) {
                return; // early-closed/abandoned: drain and drop, no buffering, no cap accrual
            }
            int remainingOff = off;
            int remaining = len;
            // Order guarantee: while overflow bytes are pending, new bytes
            // must follow them — so they go to the overflow too
            if (pendingFile() == 0 && remaining > 0) {
                int space = window.length - count;
                int n = Math.min(space, remaining);
                if (n > 0) {
                    copyIntoRing(buf, remainingOff, n);
                    remainingOff += n;
                    remaining -= n;
                }
            }
            if (remaining > 0) {
                spill(buf, remainingOff, remaining);
            }
            dataAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void copyIntoRing(byte[] buf, int off, int n) {
        int tail = (head + count) % window.length;
        int firstSegment = Math.min(n, window.length - tail);
        System.arraycopy(buf, off, window, tail, firstSegment);
        if (n > firstSegment) {
            System.arraycopy(buf, off + firstSegment, window, 0, n - firstSegment);
        }
        count += n;
    }

    private void spill(byte[] buf, int off, int len) throws IOException {
        if (bytesSpooled + len > maxSpoolPerAttachment) {
            throw new LimitExceededException(exchangeId, "spool.max-per-attachment",
                    maxSpoolPerAttachment,
                    "attachment '" + contentId + "' overflow would exceed the per-attachment spool cap");
        }
        if (overflow == null) {
            overflow = overflowSupplier.create();
            log.info("[exchange {}] spooling activated for attachment '{}'", exchangeId, contentId);
        }
        overflow.append(buf, off, len);
        fileWritten += len;
        bytesSpooled += len;
        spillListener.bytesSpooled(bytesSpooled);
    }

    /**
     * Reads up to {@code len} bytes, blocking (bounded by the read-wait
     * deadline) while the writer is active and no bytes are available.
     * Returns -1 once the writer has completed and every byte was consumed.
     */
    public int read(byte[] buf, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        lock.lock();
        try {
            long deadline = System.nanoTime() + readWaitNanos;
            while (true) {
                if (poison != null) {
                    throw asRestxopException(poison);
                }
                if (released || discard) {
                    throw new IllegalStateException("attachment stream already closed");
                }
                if (count > 0) {
                    int n = Math.min(len, count);
                    copyOutOfRing(buf, off, n);
                    return n;
                }
                if (pendingFile() > 0) {
                    int wanted = (int) Math.min(len, pendingFile());
                    int n = overflow.read(fileRead, buf, off, wanted);
                    fileRead += n;
                    return n;
                }
                if (writerComplete) {
                    return -1;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new ExchangeTimeoutException(exchangeId,
                            "no drain progress on attachment '" + contentId
                                    + "' within the read-wait deadline");
                }
                try {
                    dataAvailable.await(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RestxopException(exchangeId,
                            "consumer interrupted while awaiting attachment '" + contentId + "'", e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void copyOutOfRing(byte[] buf, int off, int n) {
        int firstSegment = Math.min(n, window.length - head);
        System.arraycopy(window, head, buf, off, firstSegment);
        if (n > firstSegment) {
            System.arraycopy(window, 0, buf, off + firstSegment, n - firstSegment);
        }
        head = (head + n) % window.length;
        count -= n;
    }

    /** The drain finished this part; wakes any blocked reader. */
    public void completeWriter() {
        lock.lock();
        try {
            writerComplete = true;
            dataAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Fails the buffer with the exchange's causal error, waking any blocked
     * reader immediately. All current and subsequent reads throw the cause.
     */
    public void poison(Throwable cause) {
        lock.lock();
        try {
            if (poison == null) {
                poison = cause;
            }
            dataAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reader closed early or abandoned the part: switch to discard mode —
     * buffered bytes are dropped and subsequent drain writes are counted and
     * discarded without buffering or spool-cap accrual.
     */
    public void discardRemaining() {
        lock.lock();
        try {
            discard = true;
            head = 0;
            count = 0;
            closeOverflowQuietly();
            dataAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Releases window and overflow storage at exchange end. Idempotent. */
    public void release() {
        lock.lock();
        try {
            if (released) {
                return;
            }
            released = true;
            head = 0;
            count = 0;
            closeOverflowQuietly();
            dataAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void closeOverflowQuietly() {
        if (overflow != null) {
            try {
                overflow.close();
            } catch (IOException e) {
                log.warn("[exchange {}] failed to close overflow store for attachment '{}'",
                        exchangeId, contentId, e);
            }
            overflow = null;
            fileRead = 0;
            fileWritten = 0;
        }
    }

    private long pendingFile() {
        return fileWritten - fileRead;
    }

    private RestxopException asRestxopException(Throwable cause) {
        if (cause instanceof RestxopException restxop) {
            return restxop;
        }
        return new ExchangeFailedException(exchangeId,
                "exchange failed while streaming attachment '" + contentId + "'", cause);
    }

    public long bytesWritten() {
        lock.lock();
        try {
            return bytesWritten;
        } finally {
            lock.unlock();
        }
    }

    public long bytesSpooled() {
        lock.lock();
        try {
            return bytesSpooled;
        } finally {
            lock.unlock();
        }
    }

    public boolean isWriterComplete() {
        lock.lock();
        try {
            return writerComplete;
        } finally {
            lock.unlock();
        }
    }

    public String contentId() {
        return contentId;
    }

    /** Bytes currently buffered in the memory window (test hook). */
    int availableInWindow() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }
}
