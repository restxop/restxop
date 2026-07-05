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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttachmentTest {

    private static final byte[] CONTENT = "attachment content bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void pathSourceDerivesFilenameAndLength(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("report.pdf");
        Files.write(file, CONTENT);

        Attachment attachment = Attachment.of(file);

        assertEquals("report.pdf", attachment.filename().orElseThrow());
        assertEquals(OptionalLong.of(CONTENT.length), attachment.contentLength());
        assertTrue(attachment.contentType().isEmpty());
        assertArrayEquals(CONTENT, attachment.contentStream().readAllBytes());
    }

    @Test
    void fileSourceBehavesLikePathSource(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("image.png");
        Files.write(file, CONTENT);

        Attachment attachment = Attachment.of(file.toFile());

        assertEquals("image.png", attachment.filename().orElseThrow());
        assertArrayEquals(CONTENT, attachment.contentStream().readAllBytes());
    }

    @Test
    void byteSourceIsDefensivelyCopiedAndSized() throws IOException {
        byte[] mutable = CONTENT.clone();
        Attachment attachment = Attachment.of(mutable);
        mutable[0] = 'X';

        assertEquals(OptionalLong.of(CONTENT.length), attachment.contentLength());
        assertArrayEquals(CONTENT, attachment.contentStream().readAllBytes());
    }

    @Test
    void byteSourceSupportsRepeatedReads() throws IOException {
        Attachment attachment = Attachment.of(CONTENT);
        assertArrayEquals(CONTENT, attachment.contentStream().readAllBytes());
        assertArrayEquals(CONTENT, attachment.contentStream().readAllBytes());
    }

    @Test
    void streamSourceHasUnknownLengthAndSingleConsumption() throws IOException {
        InputStream in = new ByteArrayInputStream(CONTENT);
        Attachment attachment = Attachment.of(in);

        assertTrue(attachment.contentLength().isEmpty());
        assertTrue(attachment.filename().isEmpty());
        assertArrayEquals(CONTENT, attachment.contentStream().readAllBytes());
        assertThrows(IllegalStateException.class, attachment::contentStream);
    }

    @Test
    void streamFactoryWithMetadata() {
        Attachment attachment = Attachment.of(
                new ByteArrayInputStream(CONTENT), "notes.txt", "text/plain");
        assertEquals("notes.txt", attachment.filename().orElseThrow());
        assertEquals("text/plain", attachment.contentType().orElseThrow());
    }

    @Test
    void builderOverridesMetadataOnAnySource(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("raw.bin");
        Files.write(file, CONTENT);

        Attachment attachment = Attachment.builder(file)
                .filename("renamed.bin")
                .contentType("application/x-custom")
                .contentLength(7)
                .build();

        assertEquals("renamed.bin", attachment.filename().orElseThrow());
        assertEquals("application/x-custom", attachment.contentType().orElseThrow());
        assertEquals(OptionalLong.of(7), attachment.contentLength());
    }

    @Test
    void builderCanClearDerivedFilename(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("secret-name.dat");
        Files.write(file, CONTENT);

        Attachment attachment = Attachment.builder(file).filename(null).build();

        assertTrue(attachment.filename().isEmpty());
    }

    @Test
    void builderRejectsNegativeLength() {
        var builder = Attachment.builder(CONTENT);
        assertThrows(IllegalArgumentException.class, () -> builder.contentLength(-1));
    }

    @Test
    void fromDataSourceCarriesMetadataAndContent() throws IOException {
        Attachment attachment = AttachmentAdapters.fromDataSource(
                new FixedDataSource("ds.bin", "application/x-ds"));

        assertEquals("ds.bin", attachment.filename().orElseThrow());
        assertEquals("application/x-ds", attachment.contentType().orElseThrow());
        assertArrayEquals(CONTENT, attachment.contentStream().readAllBytes());
    }

    @Test
    void fromDataHandlerCarriesMetadataAndContent() throws IOException {
        DataHandler handler = new DataHandler(new FixedDataSource("dh.bin", "application/x-dh"));
        Attachment attachment = AttachmentAdapters.fromDataHandler(handler);

        assertEquals("dh.bin", attachment.filename().orElseThrow());
        assertEquals("application/x-dh", attachment.contentType().orElseThrow());
        assertArrayEquals(CONTENT, attachment.contentStream().readAllBytes());
    }

    @Test
    void toDataSourceExposesAttachmentReadOnly() throws IOException {
        Attachment attachment = Attachment.builder(CONTENT)
                .filename("out.bin")
                .contentType("application/x-out")
                .build();
        DataSource source = AttachmentAdapters.toDataSource(attachment);

        assertEquals("out.bin", source.getName());
        assertEquals("application/x-out", source.getContentType());
        assertArrayEquals(CONTENT, source.getInputStream().readAllBytes());
        assertThrows(IOException.class, source::getOutputStream);
    }

    @Test
    void toDataSourceDefaultsContentType() {
        DataSource source = AttachmentAdapters.toDataSource(Attachment.of(CONTENT));
        assertEquals("application/octet-stream", source.getContentType());
    }

    private static final class FixedDataSource implements DataSource {

        private final String name;
        private final String contentType;

        FixedDataSource(String name, String contentType) {
            this.name = name;
            this.contentType = contentType;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(CONTENT);
        }

        @Override
        public java.io.OutputStream getOutputStream() throws IOException {
            throw new IOException("read-only");
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public String getName() {
            return name;
        }
    }
}
