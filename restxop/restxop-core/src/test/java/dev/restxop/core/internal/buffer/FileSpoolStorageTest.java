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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.restxop.RestxopConfig;
import dev.restxop.spi.OverflowStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FileSpoolStorageTest {

    private final FileSpoolStorage storage = new FileSpoolStorage();

    private RestxopConfig config(Path dir) {
        return RestxopConfig.builder().spoolDirectory(dir).build();
    }

    private static List<Path> spoolFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "restxop-*.spool")) {
            stream.forEach(files::add);
        }
        return files;
    }

    @Test
    void appendThenPositionalReadWhileOpen(@TempDir Path dir) throws IOException {
        byte[] first = "hello spool ".getBytes(StandardCharsets.UTF_8);
        byte[] second = "more overflow bytes".getBytes(StandardCharsets.UTF_8);

        try (OverflowStore store = storage.createOverflow("ex1", "cid1", config(dir))) {
            store.append(first, 0, first.length);
            store.append(second, 0, second.length);

            byte[] readBack = new byte[first.length + second.length];
            int n = store.read(0, readBack, 0, readBack.length);
            assertEquals(readBack.length, n);
            byte[] expected = (new String(first, StandardCharsets.UTF_8)
                    + new String(second, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(expected, readBack);

            byte[] tail = new byte[second.length];
            assertEquals(second.length, store.read(first.length, tail, 0, tail.length));
            assertArrayEquals(second, tail);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void spoolFilesAreOwnerOnly(@TempDir Path dir) throws IOException {
        try (OverflowStore store = storage.createOverflow("ex2", "cid2", config(dir))) {
            List<Path> files = spoolFiles(dir);
            assertEquals(1, files.size());
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(files.get(0));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    perms);
        }
    }

    @Test
    void closeDeletesTheFileAndIsIdempotent(@TempDir Path dir) throws IOException {
        OverflowStore store = storage.createOverflow("ex3", "cid3", config(dir));
        assertEquals(1, spoolFiles(dir).size());

        store.close();
        assertTrue(spoolFiles(dir).isEmpty(), "close must delete the spool file");
        assertDoesNotThrow(store::close);
    }

    @Test
    void hostileContentIdsAreSanitizedIntoTheFilename(@TempDir Path dir) throws IOException {
        try (OverflowStore store =
                storage.createOverflow("ex4", "../../etc/passwd<cid>", config(dir))) {
            List<Path> files = spoolFiles(dir);
            assertEquals(1, files.size(), "file must be created inside the spool directory");
            assertTrue(files.get(0).getFileName().toString().startsWith("restxop-ex4-"));
        }
    }
}
