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

import java.io.InputStream;
import java.util.SplittableRandom;

/**
 * Deterministic pseudo-random content of a fixed size: the same (seed, size)
 * always produces the same bytes, so a receiver can verify checksums without
 * the content ever existing in full anywhere.
 */
public final class GeneratedInputStream extends InputStream {

    private final SplittableRandom random;
    private long remaining;

    public GeneratedInputStream(long seed, long size) {
        this.random = new SplittableRandom(seed);
        this.remaining = size;
    }

    @Override
    public int read() {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int off, int len) {
        if (remaining <= 0) {
            return -1;
        }
        int n = (int) Math.min(len, remaining);
        for (int i = 0; i < n; i++) {
            buffer[off + i] = (byte) random.nextInt(256);
        }
        remaining -= n;
        return n;
    }
}
