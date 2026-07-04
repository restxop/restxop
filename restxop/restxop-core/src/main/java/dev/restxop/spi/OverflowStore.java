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
package dev.restxop.spi;

import java.io.Closeable;
import java.io.IOException;

/**
 * Append-only overflow storage backing one chase buffer once its memory
 * window is exceeded. Written by the drain (append) and read by the consumer
 * (positional) — calls are externally synchronized by the chase buffer, so
 * implementations need not be thread-safe.
 *
 * <p>{@link #close()} MUST delete the backing storage; it is invoked exactly
 * once on every exchange outcome (success, failure, early close,
 * abandonment).</p>
 */
public interface OverflowStore extends Closeable {

    /** Appends {@code len} bytes from {@code buf} starting at {@code off}. */
    void append(byte[] buf, int off, int len) throws IOException;

    /**
     * Reads up to {@code len} bytes at absolute {@code position} into
     * {@code buf}, returning the number of bytes read (never 0 for a
     * position below the appended length).
     */
    int read(long position, byte[] buf, int off, int len) throws IOException;

    /** Releases and deletes the backing storage. Idempotent. */
    @Override
    void close() throws IOException;
}
