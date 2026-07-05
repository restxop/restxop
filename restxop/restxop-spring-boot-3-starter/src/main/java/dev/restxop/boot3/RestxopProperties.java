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
package dev.restxop.boot3;

import dev.restxop.RestxopConfig;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Externalized configuration under the {@code restxop.} prefix — see the
 * configuration reference in contracts/public-api.md. Every tunable has a
 * documented, non-infinite default (FR-029).
 */
@ConfigurationProperties(prefix = "restxop")
public class RestxopProperties {

    /** Chase-buffer memory window per attachment part before file overflow. */
    private DataSize memoryWindowPerPart = DataSize.ofKilobytes(256);

    /** Transport scan buffer size. */
    private DataSize readBufferSize = DataSize.ofKilobytes(64);

    /** Keep the servlet multipart machinery off multipart/related requests (research R6). */
    private boolean strictMultipartResolution = true;

    private final Drain drain = new Drain();
    private final Spool spool = new Spool();
    private final Limits limits = new Limits();
    private final Timeouts timeouts = new Timeouts();
    private final LegacyCompat legacyCompat = new LegacyCompat();

    /** Builds the immutable core configuration snapshot (validates on build). */
    public RestxopConfig toConfig() {
        RestxopConfig.Builder builder = RestxopConfig.builder()
                .memoryWindowPerPart(Math.toIntExact(memoryWindowPerPart.toBytes()))
                .readBufferSize(Math.toIntExact(readBufferSize.toBytes()))
                .drainPoolSize(drain.poolSize)
                .spoolMaxPerAttachment(spool.maxPerAttachment.toBytes())
                .spoolMaxPerMessage(spool.maxPerMessage.toBytes())
                .maxRootPartBytes(limits.maxRootPartBytes.toBytes())
                .maxPartHeaderBytes(Math.toIntExact(limits.maxPartHeaderBytes.toBytes()))
                .maxParts(limits.maxParts)
                .exchangeTtl(timeouts.exchangeTtl)
                .readWait(timeouts.readWait)
                .legacyCompatEnabled(legacyCompat.enabled);
        if (spool.directory != null) {
            builder.spoolDirectory(spool.directory);
        }
        return builder.build();
    }

    public DataSize getMemoryWindowPerPart() {
        return memoryWindowPerPart;
    }

    public void setMemoryWindowPerPart(DataSize memoryWindowPerPart) {
        this.memoryWindowPerPart = memoryWindowPerPart;
    }

    public DataSize getReadBufferSize() {
        return readBufferSize;
    }

    public void setReadBufferSize(DataSize readBufferSize) {
        this.readBufferSize = readBufferSize;
    }

    public boolean isStrictMultipartResolution() {
        return strictMultipartResolution;
    }

    public void setStrictMultipartResolution(boolean strictMultipartResolution) {
        this.strictMultipartResolution = strictMultipartResolution;
    }

    public Drain getDrain() {
        return drain;
    }

    public Spool getSpool() {
        return spool;
    }

    public Limits getLimits() {
        return limits;
    }

    public Timeouts getTimeouts() {
        return timeouts;
    }

    public LegacyCompat getLegacyCompat() {
        return legacyCompat;
    }

    public static class Drain {

        /** Bounded drain workers; caller-runs fallback on saturation. */
        private int poolSize = 32;

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }
    }

    public static class Spool {

        /** Spool directory; defaults to the system temp directory. */
        private Path directory;

        /** Per-attachment spool cap; exceeding it fails the exchange. */
        private DataSize maxPerAttachment = DataSize.ofGigabytes(1);

        /** Per-message aggregate spool cap; exceeding it fails the exchange. */
        private DataSize maxPerMessage = DataSize.ofGigabytes(2);

        public Path getDirectory() {
            return directory;
        }

        public void setDirectory(Path directory) {
            this.directory = directory;
        }

        public DataSize getMaxPerAttachment() {
            return maxPerAttachment;
        }

        public void setMaxPerAttachment(DataSize maxPerAttachment) {
            this.maxPerAttachment = maxPerAttachment;
        }

        public DataSize getMaxPerMessage() {
            return maxPerMessage;
        }

        public void setMaxPerMessage(DataSize maxPerMessage) {
            this.maxPerMessage = maxPerMessage;
        }
    }

    public static class Limits {

        /** Maximum root part size. */
        private DataSize maxRootPartBytes = DataSize.ofMegabytes(16);

        /** Maximum part header block size. */
        private DataSize maxPartHeaderBytes = DataSize.ofKilobytes(64);

        /** Maximum number of parts per message. */
        private int maxParts = 1000;

        public DataSize getMaxRootPartBytes() {
            return maxRootPartBytes;
        }

        public void setMaxRootPartBytes(DataSize maxRootPartBytes) {
            this.maxRootPartBytes = maxRootPartBytes;
        }

        public DataSize getMaxPartHeaderBytes() {
            return maxPartHeaderBytes;
        }

        public void setMaxPartHeaderBytes(DataSize maxPartHeaderBytes) {
            this.maxPartHeaderBytes = maxPartHeaderBytes;
        }

        public int getMaxParts() {
            return maxParts;
        }

        public void setMaxParts(int maxParts) {
            this.maxParts = maxParts;
        }
    }

    public static class Timeouts {

        /** Total exchange lifetime, enforced by the reaper. */
        private Duration exchangeTtl = Duration.ofMinutes(10);

        /** Maximum consumer wait for drain progress on a chase buffer. */
        private Duration readWait = Duration.ofSeconds(60);

        public Duration getExchangeTtl() {
            return exchangeTtl;
        }

        public void setExchangeTtl(Duration exchangeTtl) {
            this.exchangeTtl = exchangeTtl;
        }

        public Duration getReadWait() {
            return readWait;
        }

        public void setReadWait(Duration readWait) {
            this.readWait = readWait;
        }
    }

    public static class LegacyCompat {

        /** Deprecated composite/related interop mode; off by default. */
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
