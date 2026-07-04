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

import dev.restxop.RestxopConfig;
import dev.restxop.spi.OverflowStore;
import dev.restxop.spi.SpoolStorage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Default {@link SpoolStorage}: plain files in the configured spool
 * directory, created with owner-only permissions and deleted when the store
 * closes (which the exchange guarantees on every outcome).
 */
public final class FileSpoolStorage implements SpoolStorage {

    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

    @Override
    public OverflowStore createOverflow(String exchangeId, String contentId, RestxopConfig config)
            throws IOException {
        String prefix = "restxop-" + sanitize(exchangeId) + "-" + sanitize(contentId) + "-";
        Path file;
        try {
            file = Files.createTempFile(config.spoolDirectory(), prefix, ".spool", OWNER_ONLY);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. Windows): fall back to default temp
            // file attributes, which are already user-private there
            file = Files.createTempFile(config.spoolDirectory(), prefix, ".spool");
        }
        return new FileOverflowStore(file);
    }

    private static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return sb.toString();
    }

    private static final class FileOverflowStore implements OverflowStore {

        private final Path file;
        private final FileChannel channel;
        private long appendPosition;
        private boolean closed;

        FileOverflowStore(Path file) throws IOException {
            this.file = file;
            this.channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        }

        @Override
        public void append(byte[] buf, int off, int len) throws IOException {
            ByteBuffer src = ByteBuffer.wrap(buf, off, len);
            while (src.hasRemaining()) {
                appendPosition += channel.write(src, appendPosition);
            }
        }

        @Override
        public int read(long position, byte[] buf, int off, int len) throws IOException {
            return channel.read(ByteBuffer.wrap(buf, off, len), position);
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                channel.close();
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }
}
