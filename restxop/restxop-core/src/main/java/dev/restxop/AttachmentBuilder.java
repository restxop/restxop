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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builder for source-backed {@link Attachment} instances, allowing filename,
 * content type, and advisory length overrides on any source. Obtain via the
 * {@code Attachment.builder(...)} factories.
 */
public final class AttachmentBuilder {

    private interface ContentSource {
        InputStream open();
    }

    private final ContentSource source;
    private String filename;
    private String contentType;
    private long contentLength = -1;

    private AttachmentBuilder(ContentSource source) {
        this.source = source;
    }

    static AttachmentBuilder forPath(Path path) {
        Objects.requireNonNull(path, "path");
        AttachmentBuilder builder = new AttachmentBuilder(() -> {
            try {
                return Files.newInputStream(path);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot open attachment source " + path, e);
            }
        });
        Path name = path.getFileName();
        if (name != null) {
            builder.filename = name.toString();
        }
        try {
            builder.contentLength = Files.size(path);
        } catch (IOException ignored) {
            // Length stays advisory-unknown; opening the stream will surface
            // the real error at write time (FR-014)
        }
        return builder;
    }

    static AttachmentBuilder forBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        byte[] copy = bytes.clone();
        AttachmentBuilder builder = new AttachmentBuilder(() -> new ByteArrayInputStream(copy));
        builder.contentLength = copy.length;
        return builder;
    }

    static AttachmentBuilder forStream(InputStream in) {
        Objects.requireNonNull(in, "in");
        AtomicBoolean consumed = new AtomicBoolean();
        return new AttachmentBuilder(() -> {
            if (!consumed.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "stream-backed attachment supports a single sequential consumption");
            }
            return in;
        });
    }

    /** Sets (or clears, with null) the filename carried on the wire. */
    public AttachmentBuilder filename(String filename) {
        this.filename = filename;
        return this;
    }

    /** Sets (or clears, with null) the content type carried on the wire. */
    public AttachmentBuilder contentType(String contentType) {
        this.contentType = contentType;
        return this;
    }

    /** Overrides the advisory content length. */
    public AttachmentBuilder contentLength(long contentLength) {
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength must be >= 0, was " + contentLength);
        }
        this.contentLength = contentLength;
        return this;
    }

    public Attachment build() {
        return new SourceAttachment(source::open, filename, contentType, contentLength);
    }

    private static final class SourceAttachment implements Attachment {

        private final ContentSource source;
        private final String filename;
        private final String contentType;
        private final long contentLength;

        SourceAttachment(ContentSource source, String filename, String contentType, long contentLength) {
            this.source = source;
            this.filename = filename;
            this.contentType = contentType;
            this.contentLength = contentLength;
        }

        @Override
        public InputStream contentStream() {
            return source.open();
        }

        @Override
        public java.util.Optional<String> filename() {
            return java.util.Optional.ofNullable(filename);
        }

        @Override
        public java.util.Optional<String> contentType() {
            return java.util.Optional.ofNullable(contentType);
        }

        @Override
        public java.util.OptionalLong contentLength() {
            return contentLength < 0 ? java.util.OptionalLong.empty()
                    : java.util.OptionalLong.of(contentLength);
        }
    }
}
