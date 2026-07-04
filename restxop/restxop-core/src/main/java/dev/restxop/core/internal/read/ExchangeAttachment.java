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
package dev.restxop.core.internal.read;

import dev.restxop.Attachment;
import dev.restxop.core.internal.buffer.ChaseBuffer;
import dev.restxop.spi.AttachmentInfo;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Exchange-backed lazy attachment wired at payload deserialization time
 * (FR-016). Its stream chases the drain through the part's chase buffer;
 * metadata is bound when the drain reaches the part's headers (FR-019).
 * {@code contentStream()} is idempotent — one stream instance, single
 * sequential consumption; closing early discards the remainder.
 */
final class ExchangeAttachment implements Attachment, AttachmentInfo {

    private final String contentId;
    private final ChaseBuffer buffer;
    private final DrainTask drain;
    private volatile String filename;
    private volatile String contentType;
    private AttachmentStream stream;

    ExchangeAttachment(String contentId, ChaseBuffer buffer, DrainTask drain) {
        this.contentId = contentId;
        this.buffer = buffer;
        this.drain = drain;
    }

    ChaseBuffer buffer() {
        return buffer;
    }

    void bindMetadata(Optional<String> filename, Optional<String> contentType) {
        this.filename = filename.orElse(null);
        this.contentType = contentType.orElse(null);
    }

    @Override
    public synchronized InputStream contentStream() {
        if (stream == null) {
            stream = new AttachmentStream();
        }
        return stream;
    }

    @Override
    public String contentId() {
        return contentId;
    }

    @Override
    public Optional<String> filename() {
        return Optional.ofNullable(filename);
    }

    @Override
    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    @Override
    public OptionalLong contentLength() {
        return OptionalLong.empty(); // parts carry no length on the wire
    }

    private final class AttachmentStream extends InputStream {

        private final byte[] single = new byte[1];
        private boolean exhausted;
        private boolean consumedReported;

        @Override
        public int read() throws IOException {
            int n = read(single, 0, 1);
            return n < 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            if (exhausted) {
                return -1;
            }
            drain.ensureRunning();
            int n = buffer.read(b, off, len);
            if (n == -1) {
                exhausted = true;
                reportConsumed();
            }
            return n;
        }

        @Override
        public synchronized void close() {
            if (exhausted) {
                return;
            }
            exhausted = true;
            buffer.discardRemaining();
            reportConsumed();
        }

        private void reportConsumed() {
            if (!consumedReported) {
                consumedReported = true;
                drain.attachmentFinished(ExchangeAttachment.this);
            }
        }
    }
}
