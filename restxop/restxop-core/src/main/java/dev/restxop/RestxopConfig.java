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
package dev.restxop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration snapshot for one exchange. Obtain via
 * {@link #defaults()} or {@link #builder()}; every tunable has a documented,
 * non-infinite default. Validation is performed at {@link Builder#build()}
 * so misconfiguration fails fast at startup, not mid-exchange.
 */
public final class RestxopConfig {

    /** 256 KiB chase-buffer memory window per attachment part. */
    public static final int DEFAULT_MEMORY_WINDOW_PER_PART = 256 * 1024;
    /** 32 bounded drain workers; caller-runs fallback on saturation. */
    public static final int DEFAULT_DRAIN_POOL_SIZE = 32;
    /** 1 GiB spool cap per attachment. */
    public static final long DEFAULT_SPOOL_MAX_PER_ATTACHMENT = 1024L * 1024 * 1024;
    /** 2 GiB spool cap per message. */
    public static final long DEFAULT_SPOOL_MAX_PER_MESSAGE = 2048L * 1024 * 1024;
    /** 16 MiB maximum root part size. */
    public static final long DEFAULT_MAX_ROOT_PART_BYTES = 16L * 1024 * 1024;
    /** 64 KiB maximum part header block size. */
    public static final int DEFAULT_MAX_PART_HEADER_BYTES = 64 * 1024;
    /** 1000 parts maximum per message. */
    public static final int DEFAULT_MAX_PARTS = 1000;
    /** 10 minute total exchange lifetime. */
    public static final Duration DEFAULT_EXCHANGE_TTL = Duration.ofMinutes(10);
    /** 60 second maximum consumer wait for drain progress on a chase buffer. */
    public static final Duration DEFAULT_READ_WAIT = Duration.ofSeconds(60);
    /** 8 KiB transport scan buffer. */
    public static final int DEFAULT_READ_BUFFER_SIZE = 8 * 1024;

    private final int memoryWindowPerPart;
    private final int drainPoolSize;
    private final Path spoolDirectory;
    private final long spoolMaxPerAttachment;
    private final long spoolMaxPerMessage;
    private final long maxRootPartBytes;
    private final int maxPartHeaderBytes;
    private final int maxParts;
    private final Duration exchangeTtl;
    private final Duration readWait;
    private final int readBufferSize;
    private final boolean legacyCompatEnabled;

    private RestxopConfig(Builder b) {
        this.memoryWindowPerPart = b.memoryWindowPerPart;
        this.drainPoolSize = b.drainPoolSize;
        this.spoolDirectory = b.spoolDirectory;
        this.spoolMaxPerAttachment = b.spoolMaxPerAttachment;
        this.spoolMaxPerMessage = b.spoolMaxPerMessage;
        this.maxRootPartBytes = b.maxRootPartBytes;
        this.maxPartHeaderBytes = b.maxPartHeaderBytes;
        this.maxParts = b.maxParts;
        this.exchangeTtl = b.exchangeTtl;
        this.readWait = b.readWait;
        this.readBufferSize = b.readBufferSize;
        this.legacyCompatEnabled = b.legacyCompatEnabled;
    }

    /** Configuration with all documented defaults. */
    public static RestxopConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int memoryWindowPerPart() {
        return memoryWindowPerPart;
    }

    public int drainPoolSize() {
        return drainPoolSize;
    }

    public Path spoolDirectory() {
        return spoolDirectory;
    }

    public long spoolMaxPerAttachment() {
        return spoolMaxPerAttachment;
    }

    public long spoolMaxPerMessage() {
        return spoolMaxPerMessage;
    }

    public long maxRootPartBytes() {
        return maxRootPartBytes;
    }

    public int maxPartHeaderBytes() {
        return maxPartHeaderBytes;
    }

    public int maxParts() {
        return maxParts;
    }

    public Duration exchangeTtl() {
        return exchangeTtl;
    }

    public Duration readWait() {
        return readWait;
    }

    public int readBufferSize() {
        return readBufferSize;
    }

    public boolean legacyCompatEnabled() {
        return legacyCompatEnabled;
    }

    /** Builder for {@link RestxopConfig}; validation happens in {@link #build()}. */
    public static final class Builder {

        private int memoryWindowPerPart = DEFAULT_MEMORY_WINDOW_PER_PART;
        private int drainPoolSize = DEFAULT_DRAIN_POOL_SIZE;
        private Path spoolDirectory = Path.of(System.getProperty("java.io.tmpdir"));
        private long spoolMaxPerAttachment = DEFAULT_SPOOL_MAX_PER_ATTACHMENT;
        private long spoolMaxPerMessage = DEFAULT_SPOOL_MAX_PER_MESSAGE;
        private long maxRootPartBytes = DEFAULT_MAX_ROOT_PART_BYTES;
        private int maxPartHeaderBytes = DEFAULT_MAX_PART_HEADER_BYTES;
        private int maxParts = DEFAULT_MAX_PARTS;
        private Duration exchangeTtl = DEFAULT_EXCHANGE_TTL;
        private Duration readWait = DEFAULT_READ_WAIT;
        private int readBufferSize = DEFAULT_READ_BUFFER_SIZE;
        private boolean legacyCompatEnabled;

        private Builder() {
        }

        public Builder memoryWindowPerPart(int bytes) {
            this.memoryWindowPerPart = bytes;
            return this;
        }

        public Builder drainPoolSize(int size) {
            this.drainPoolSize = size;
            return this;
        }

        public Builder spoolDirectory(Path directory) {
            this.spoolDirectory = Objects.requireNonNull(directory, "spoolDirectory");
            return this;
        }

        public Builder spoolMaxPerAttachment(long bytes) {
            this.spoolMaxPerAttachment = bytes;
            return this;
        }

        public Builder spoolMaxPerMessage(long bytes) {
            this.spoolMaxPerMessage = bytes;
            return this;
        }

        public Builder maxRootPartBytes(long bytes) {
            this.maxRootPartBytes = bytes;
            return this;
        }

        public Builder maxPartHeaderBytes(int bytes) {
            this.maxPartHeaderBytes = bytes;
            return this;
        }

        public Builder maxParts(int count) {
            this.maxParts = count;
            return this;
        }

        public Builder exchangeTtl(Duration ttl) {
            this.exchangeTtl = Objects.requireNonNull(ttl, "exchangeTtl");
            return this;
        }

        public Builder readWait(Duration readWait) {
            this.readWait = Objects.requireNonNull(readWait, "readWait");
            return this;
        }

        public Builder readBufferSize(int bytes) {
            this.readBufferSize = bytes;
            return this;
        }

        public Builder legacyCompatEnabled(boolean enabled) {
            this.legacyCompatEnabled = enabled;
            return this;
        }

        public RestxopConfig build() {
            requirePositive(memoryWindowPerPart, "memory-window-per-part");
            requirePositive(drainPoolSize, "drain.pool-size");
            requirePositive(spoolMaxPerAttachment, "spool.max-per-attachment");
            requirePositive(spoolMaxPerMessage, "spool.max-per-message");
            requirePositive(maxRootPartBytes, "limits.max-root-part-bytes");
            requirePositive(maxPartHeaderBytes, "limits.max-part-header-bytes");
            requirePositive(maxParts, "limits.max-parts");
            requirePositiveDuration(exchangeTtl, "timeouts.exchange-ttl");
            requirePositiveDuration(readWait, "timeouts.read-wait");
            if (readBufferSize < 1024) {
                throw new IllegalArgumentException("read-buffer-size must be at least 1KB, was " + readBufferSize);
            }
            if (memoryWindowPerPart > spoolMaxPerAttachment) {
                throw new IllegalArgumentException("memory-window-per-part (" + memoryWindowPerPart
                        + ") must not exceed spool.max-per-attachment (" + spoolMaxPerAttachment + ")");
            }
            if (spoolMaxPerAttachment > spoolMaxPerMessage) {
                throw new IllegalArgumentException("spool.max-per-attachment (" + spoolMaxPerAttachment
                        + ") must not exceed spool.max-per-message (" + spoolMaxPerMessage + ")");
            }
            if (readWait.compareTo(exchangeTtl) > 0) {
                throw new IllegalArgumentException("timeouts.read-wait (" + readWait
                        + ") must not exceed timeouts.exchange-ttl (" + exchangeTtl + ")");
            }
            if (!Files.isDirectory(spoolDirectory) || !Files.isWritable(spoolDirectory)) {
                throw new IllegalArgumentException(
                        "spool.directory must exist and be writable: " + spoolDirectory);
            }
            return new RestxopConfig(this);
        }

        private static void requirePositive(long value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive, was " + value);
            }
        }

        private static void requirePositiveDuration(Duration value, String name) {
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive, was " + value);
            }
        }
    }
}
