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
package dev.restxop.core.internal.write;

import dev.restxop.Attachment;
import dev.restxop.RestxopConfig;
import dev.restxop.core.internal.exchange.Exchange;
import dev.restxop.spi.AttachmentCollector;
import dev.restxop.spi.RootPartCodec;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Streams one message per wire-format §1–5: leading delimiter, JSON root
 * part (serialized through the {@link RootPartCodec}, which registers
 * attachments in encounter order), one part per distinct attachment
 * instance, closing delimiter. Attachment content is copied source-to-output
 * in bounded chunks — never fully buffered (FR-013). Single-use: one writer
 * per message.
 */
public final class MessageWriter {

    private static final String CRLF = "\r\n";

    /**
     * Identifier generation for one message. Production uses
     * {@link #random()}; conformance tests inject deterministic values so
     * output is fixture-comparable byte-for-byte.
     */
    public record WriterIds(String boundary, String rootContentId,
            Supplier<String> attachmentContentIds) {

        public static WriterIds random() {
            return new WriterIds(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    () -> UUID.randomUUID().toString());
        }
    }

    private final RestxopConfig config;
    private final RootPartCodec codec;
    private final WriterIds ids;
    private final boolean legacy;
    private final String responseId;

    public MessageWriter(RestxopConfig config, RootPartCodec codec) {
        this(config, codec, WriterIds.random());
    }

    public MessageWriter(RestxopConfig config, RootPartCodec codec, WriterIds ids) {
        this.config = config;
        this.codec = codec;
        this.ids = ids;
        this.legacy = config.legacyCompatEnabled();
        this.responseId = legacy ? UUID.randomUUID().toString() : null;
    }

    /** The outer Content-Type header value, canonically quoted (§1; legacy §7). */
    public String contentType() {
        if (legacy) {
            return "composite/related; type=\"application/json\"; boundary=\"" + ids.boundary()
                    + "\"; start=\"<mainpart>\"";
        }
        return "multipart/related; type=\"application/json\"; boundary=\"" + ids.boundary()
                + "\"; start=\"<" + ids.rootContentId() + ">\"";
    }

    /**
     * The legacy {@code Response-ID} HTTP header value (wire-format §7) —
     * present only in compat mode; client/server integrations set it on the
     * outgoing message.
     */
    public Optional<String> responseIdHeader() {
        return Optional.ofNullable(responseId);
    }

    private String rootContentId() {
        return legacy ? "mainpart" : ids.rootContentId();
    }

    /**
     * Writes the complete message body. Any failure (inaccessible source,
     * serialization failure, output failure) propagates so the caller can
     * fail the exchange (FR-014); opened attachment sources are always
     * closed.
     */
    public void write(Exchange exchange, Object payload, OutputStream out) throws IOException {
        Collector collector = new Collector();

        writeAscii(out, CRLF + "--" + ids.boundary() + "\r\n"
                + "Content-ID: <" + rootContentId() + ">\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Transfer-Encoding: binary\r\n\r\n");
        try {
            codec.writeRoot(payload, new CloseShield(out), collector);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }

        byte[] copyBuffer = new byte[config.readBufferSize()];
        for (RegisteredAttachment registered : collector.ordered) {
            exchange.checkTtl();
            Attachment attachment = registered.attachment();
            StringBuilder headers = new StringBuilder(128)
                    .append(CRLF + "--").append(ids.boundary()).append(CRLF);
            if (legacy) {
                // §7: bare (unbracketed) identifier, legacy disposition shape
                headers.append("Content-ID: ").append(registered.contentId()).append(CRLF);
            } else {
                headers.append("Content-ID: <").append(registered.contentId()).append(">\r\n");
            }
            headers.append("Content-Type: ")
                    .append(attachment.contentType().orElse("application/octet-stream"))
                    .append(CRLF);
            attachment.filename().ifPresent(filename -> {
                if (legacy) {
                    headers.append("Content-Disposition: attachment;name=\"")
                            .append(filename.replace("\\", "\\\\").replace("\"", "\\\""))
                            .append("\"\r\n");
                } else {
                    headers.append("Content-Disposition: attachment; ")
                            .append(dispositionFilename(filename)).append(CRLF);
                }
            });
            headers.append("Content-Transfer-Encoding: binary\r\n\r\n");
            writeAscii(out, headers.toString());

            try (InputStream source = openSource(attachment)) {
                int n;
                while ((n = source.read(copyBuffer, 0, copyBuffer.length)) != -1) {
                    out.write(copyBuffer, 0, n);
                }
            }
        }

        writeAscii(out, CRLF + "--" + ids.boundary() + "--\r\n");
        out.flush();
    }

    private static InputStream openSource(Attachment attachment) throws IOException {
        try {
            return attachment.contentStream();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * RFC 6266 filename parameter: ASCII names travel as a quoted string;
     * non-ASCII names travel percent-encoded via the RFC 5987
     * {@code filename*} ext-value (wire-format §3).
     */
    private static String dispositionFilename(String filename) {
        boolean ascii = filename.chars().allMatch(c -> c >= 0x20 && c < 0x7F);
        if (ascii) {
            return "filename=\"" + filename.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        StringBuilder encoded = new StringBuilder("filename*=UTF-8''");
        for (byte b : filename.getBytes(StandardCharsets.UTF_8)) {
            if (isAttrChar(b)) {
                encoded.append((char) b);
            } else {
                encoded.append('%').append(String.format("%02X", b));
            }
        }
        return encoded.toString();
    }

    /** RFC 5987 attr-char: characters allowed unencoded in an ext-value. */
    private static boolean isAttrChar(byte b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                || b == '!' || b == '#' || b == '$' || b == '&' || b == '+' || b == '-'
                || b == '.' || b == '^' || b == '_' || b == '`' || b == '|' || b == '~';
    }

    private static void writeAscii(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private record RegisteredAttachment(Attachment attachment, String contentId) {
    }

    /** Identity-map collector: one part and one Content-ID per instance (FR-012). */
    private final class Collector implements AttachmentCollector, ReferenceStyleAware {

        @Override
        public boolean bareReferences() {
            return legacy;
        }

        private final Map<Attachment, String> byIdentity = new IdentityHashMap<>();
        private final List<RegisteredAttachment> ordered = new ArrayList<>();

        @Override
        public String register(Attachment attachment) {
            String existing = byIdentity.get(attachment);
            if (existing != null) {
                return existing;
            }
            String contentId = ids.attachmentContentIds().get();
            byIdentity.put(attachment, contentId);
            ordered.add(new RegisteredAttachment(attachment, contentId));
            return contentId;
        }
    }

    /** Keeps codecs from closing the transport stream. */
    private static final class CloseShield extends FilterOutputStream {

        CloseShield(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}
