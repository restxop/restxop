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

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A binary value carried by a payload object. The single attachment type the
 * serialization machinery recognizes: source-backed when producing a message
 * (created via the static factories), exchange-backed and lazily streamed
 * when consuming one.
 *
 * <p>Instance identity is significant: referencing the same instance from
 * several places in one payload transmits its content once, and all
 * references resolve to one shared instance on the receiving side.</p>
 */
public interface Attachment {

    /**
     * The attachment's content as a stream for single sequential
     * consumption. On the read side this blocks (bounded by the configured
     * read-wait/TTL deadlines) until bytes arrive, returns the same stream
     * instance on repeated calls, and throws the restxop error hierarchy on
     * exchange failure. Closing before full consumption is legal and skips
     * the remainder.
     */
    InputStream contentStream();

    /** Filename carried via the part's Content-Disposition, when present. */
    Optional<String> filename();

    /** Content type carried via the part's Content-Type, when present. */
    Optional<String> contentType();

    /** Advisory content length: known for file/byte sources, otherwise empty. */
    OptionalLong contentLength();

    /** Attachment backed by a file path; length and filename derive from the path. */
    static Attachment of(Path path) {
        return builder(path).build();
    }

    /** Attachment backed by a file; length and filename derive from the file. */
    static Attachment of(File file) {
        return builder(file).build();
    }

    /** Attachment backed by an in-memory byte array. */
    static Attachment of(byte[] bytes) {
        return builder(bytes).build();
    }

    /** Attachment backed by a one-shot stream of unknown length. */
    static Attachment of(InputStream in) {
        return builder(in).build();
    }

    /** Stream-backed attachment with explicit metadata. */
    static Attachment of(InputStream in, String filename, String contentType) {
        return builder(in).filename(filename).contentType(contentType).build();
    }

    /** Builder over a path source, for metadata overrides. */
    static AttachmentBuilder builder(Path path) {
        return AttachmentBuilder.forPath(path);
    }

    /** Builder over a file source, for metadata overrides. */
    static AttachmentBuilder builder(File file) {
        return AttachmentBuilder.forPath(file.toPath());
    }

    /** Builder over a byte-array source, for metadata overrides. */
    static AttachmentBuilder builder(byte[] bytes) {
        return AttachmentBuilder.forBytes(bytes);
    }

    /** Builder over a one-shot stream source, for metadata overrides. */
    static AttachmentBuilder builder(InputStream in) {
        return AttachmentBuilder.forStream(in);
    }
}
