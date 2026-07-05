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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestxopConfigTest {

    @Test
    void defaultsMatchDocumentedValues() {
        RestxopConfig config = RestxopConfig.defaults();
        assertEquals(256 * 1024, config.memoryWindowPerPart());
        assertEquals(32, config.drainPoolSize());
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")), config.spoolDirectory());
        assertEquals(1024L * 1024 * 1024, config.spoolMaxPerAttachment());
        assertEquals(2048L * 1024 * 1024, config.spoolMaxPerMessage());
        assertEquals(16L * 1024 * 1024, config.maxRootPartBytes());
        assertEquals(64 * 1024, config.maxPartHeaderBytes());
        assertEquals(1000, config.maxParts());
        assertEquals(Duration.ofMinutes(10), config.exchangeTtl());
        assertEquals(Duration.ofSeconds(60), config.readWait());
        assertEquals(64 * 1024, config.readBufferSize());
        assertFalse(config.legacyCompatEnabled());
    }

    @Test
    void builderOverridesAreApplied(@TempDir Path dir) {
        RestxopConfig config = RestxopConfig.builder()
                .memoryWindowPerPart(1024)
                .drainPoolSize(4)
                .spoolDirectory(dir)
                .spoolMaxPerAttachment(2048)
                .spoolMaxPerMessage(4096)
                .maxRootPartBytes(512)
                .maxPartHeaderBytes(256)
                .maxParts(5)
                .exchangeTtl(Duration.ofSeconds(30))
                .readWait(Duration.ofSeconds(5))
                .readBufferSize(2048)
                .legacyCompatEnabled(true)
                .build();
        assertEquals(1024, config.memoryWindowPerPart());
        assertEquals(4, config.drainPoolSize());
        assertEquals(dir, config.spoolDirectory());
        assertEquals(2048, config.spoolMaxPerAttachment());
        assertEquals(4096, config.spoolMaxPerMessage());
        assertEquals(512, config.maxRootPartBytes());
        assertEquals(256, config.maxPartHeaderBytes());
        assertEquals(5, config.maxParts());
        assertEquals(Duration.ofSeconds(30), config.exchangeTtl());
        assertEquals(Duration.ofSeconds(5), config.readWait());
        assertEquals(2048, config.readBufferSize());
        assertTrue(config.legacyCompatEnabled());
    }

    @Test
    void rejectsNonPositiveSizes() {
        RestxopConfig.Builder invalid1 = RestxopConfig.builder().memoryWindowPerPart(0);
        assertThrows(IllegalArgumentException.class, invalid1::build);
        RestxopConfig.Builder invalid2 = RestxopConfig.builder().drainPoolSize(-1);
        assertThrows(IllegalArgumentException.class, invalid2::build);
        RestxopConfig.Builder invalid3 = RestxopConfig.builder().maxParts(0);
        assertThrows(IllegalArgumentException.class, invalid3::build);
        RestxopConfig.Builder invalid4 = RestxopConfig.builder().maxRootPartBytes(-5);
        assertThrows(IllegalArgumentException.class, invalid4::build);
    }

    @Test
    void rejectsWindowLargerThanPerAttachmentCap() {
        RestxopConfig.Builder invalid5 = RestxopConfig.builder() .memoryWindowPerPart(4096) .spoolMaxPerAttachment(1024) ;
        assertThrows(IllegalArgumentException.class, invalid5::build);
    }

    @Test
    void rejectsPerAttachmentCapAbovePerMessageCap() {
        RestxopConfig.Builder invalid6 = RestxopConfig.builder() .spoolMaxPerAttachment(4096) .spoolMaxPerMessage(2048) ;
        assertThrows(IllegalArgumentException.class, invalid6::build);
    }

    @Test
    void rejectsReadWaitAboveExchangeTtl() {
        RestxopConfig.Builder invalid7 = RestxopConfig.builder() .readWait(Duration.ofMinutes(20)) .exchangeTtl(Duration.ofMinutes(10)) ;
        assertThrows(IllegalArgumentException.class, invalid7::build);
    }

    @Test
    void rejectsReadBufferBelowOneKiB() {
        RestxopConfig.Builder invalid8 = RestxopConfig.builder().readBufferSize(512);
        assertThrows(IllegalArgumentException.class, invalid8::build);
    }

    @Test
    void rejectsZeroTimeouts() {
        RestxopConfig.Builder invalid9 = RestxopConfig.builder().exchangeTtl(Duration.ZERO);
        assertThrows(IllegalArgumentException.class, invalid9::build);
        RestxopConfig.Builder invalid10 = RestxopConfig.builder().readWait(Duration.ZERO);
        assertThrows(IllegalArgumentException.class, invalid10::build);
    }

    @Test
    void rejectsMissingSpoolDirectory(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist");
        RestxopConfig.Builder invalid11 = RestxopConfig.builder().spoolDirectory(missing);
        assertThrows(IllegalArgumentException.class, invalid11::build);
    }
}
