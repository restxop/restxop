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

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Interop adapters between {@link Attachment} and the Jakarta Activation
 * types used by the legacy library's API ({@code DataHandler} /
 * {@code DataSource}) — the migration path for existing payload models.
 *
 * <p>Requires {@code jakarta.activation-api} on the classpath (an optional
 * dependency of restxop-core); nothing else in the library touches it.</p>
 */
public final class AttachmentAdapters {

    private AttachmentAdapters() {
    }

    /** Adapts a DataHandler source into a stream-backed attachment. */
    public static Attachment fromDataHandler(DataHandler handler) {
        Objects.requireNonNull(handler, "handler");
        InputStream stream;
        try {
            stream = handler.getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open DataHandler content", e);
        }
        return Attachment.builder(stream)
                .filename(handler.getName())
                .contentType(handler.getContentType())
                .build();
    }

    /** Adapts a DataSource into a stream-backed attachment. */
    public static Attachment fromDataSource(DataSource source) {
        Objects.requireNonNull(source, "source");
        InputStream stream;
        try {
            stream = source.getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open DataSource content", e);
        }
        return Attachment.builder(stream)
                .filename(source.getName())
                .contentType(source.getContentType())
                .build();
    }

    /** Exposes an attachment as a read-only DataSource. */
    public static DataSource toDataSource(Attachment attachment) {
        Objects.requireNonNull(attachment, "attachment");
        return new AttachmentDataSource(attachment);
    }

    private static final class AttachmentDataSource implements DataSource {

        private final Attachment attachment;

        AttachmentDataSource(Attachment attachment) {
            this.attachment = attachment;
        }

        @Override
        public InputStream getInputStream() {
            return attachment.contentStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            throw new IOException("restxop attachments are read-only");
        }

        @Override
        public String getContentType() {
            return attachment.contentType().orElse("application/octet-stream");
        }

        @Override
        public String getName() {
            return attachment.filename().orElse(null);
        }
    }
}
