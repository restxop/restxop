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
package dev.restxop.testkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.restxop.Attachment;
import dev.restxop.AttachmentUnavailableException;
import dev.restxop.MalformedMessageException;
import dev.restxop.RestxopConfig;
import dev.restxop.RestxopException;
import dev.restxop.core.internal.buffer.FileSpoolStorage;
import dev.restxop.core.internal.exchange.Exchange;
import dev.restxop.core.internal.read.MessageReader;
import dev.restxop.core.internal.read.ReadResult;
import dev.restxop.core.internal.write.MessageWriter;
import dev.restxop.spi.ResolvableTypeInfo;
import dev.restxop.spi.RootPartCodec;
import dev.restxop.testkit.model.LegacyPayload;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * US5 legacy interoperability (tag {@code legacy}). The captured
 * legacy-format wire fixtures are the conformance authority (wire-format
 * §7): with compat off they are rejected as an unsupported media type; with
 * compat on they are read with byte-exact attachment delivery — notably
 * WITHOUT the two trailing bytes the legacy reader used to append — and
 * compat writes carry the legacy shapes legacy readers expect.
 */
@Tag("legacy")
@Timeout(60)
public abstract class LegacyCompatSuite {

    protected static final String LEGACY_BOUNDARY = "39783eb8-26f4-4d49-bc54-f44ca1dff15c";
    protected static final String REPEATS_BOUNDARY = "3978--3978--3968--3958--3948";
    protected static final byte[] LEGACY_ATTACHMENT_CONTENT =
            "This is a test".getBytes(StandardCharsets.ISO_8859_1);

    @TempDir
    protected Path spoolDir;

    private final List<MessageReader> openReaders = new ArrayList<>();

    /** The codec under test (a real serializer; legacy JSON is ordinary JSON). */
    protected abstract RootPartCodec codec();

    @AfterEach
    void closeReaders() {
        openReaders.forEach(MessageReader::close);
        openReaders.clear();
    }

    protected RestxopConfig config(boolean compat) {
        return RestxopConfig.builder()
                .spoolDirectory(spoolDir)
                .legacyCompatEnabled(compat)
                .build();
    }

    private MessageReader reader(boolean compat) {
        MessageReader reader = new MessageReader(config(compat), codec(),
                new FileSpoolStorage(), List.of());
        openReaders.add(reader);
        return reader;
    }

    protected static String legacyContentType(String boundary) {
        return "composite/related; type=\"application/json\"; boundary=\"" + boundary
                + "\"; start=\"<mainpart>\"";
    }

    private static WireFixture legacyFixture(String name, String boundary) {
        return Fixtures.loadBodyOnly("legacy/" + name, legacyContentType(boundary));
    }

    private ReadResult<LegacyPayload> read(boolean compat, WireFixture fixture) {
        return reader(compat).read(fixture.contentType(),
                new ByteArrayInputStream(fixture.body()),
                ResolvableTypeInfo.of(LegacyPayload.class), null);
    }

    // ------------------------------------------------------------------
    // Compat off: legacy messages are rejected
    // ------------------------------------------------------------------

    @Test
    protected void compatOffRejectsTheLegacyMediaType() {
        WireFixture fixture = legacyFixture("message.http", LEGACY_BOUNDARY);
        MalformedMessageException error = assertThrows(MalformedMessageException.class,
                () -> read(false, fixture));
        assertTrue(error.getMessage().contains("composite/related"), error.getMessage());
    }

    // ------------------------------------------------------------------
    // Compat on: captured messages read byte-exactly
    // ------------------------------------------------------------------

    @Test
    protected void compatOnReadsTheCapturedMessageByteExact() throws IOException {
        LegacyPayload payload = read(true, legacyFixture("message.http", LEGACY_BOUNDARY))
                .payload();

        assertEquals("String Value", payload.field1);
        assertNotNull(payload.resource1, "bare (non-cid:) href must resolve");
        byte[] received = payload.resource1.contentStream().readAllBytes();
        assertArrayEquals(LEGACY_ATTACHMENT_CONTENT, received,
                "byte-exact: exactly 14 bytes, no legacy +2-byte trailing CRLF");
        assertEquals("Test-123", payload.resource1.filename().orElseThrow(),
                "legacy 'name' disposition parameter is exposed as the filename");
    }

    @Test
    protected void compatOnReadsTheAdversarialRepeatBoundaryCapture() throws IOException {
        LegacyPayload payload = read(true, legacyFixture("message-repeats.http",
                REPEATS_BOUNDARY)).payload();

        assertEquals("--3978--3978--3978 ---String Value 2", payload.field1);
        assertArrayEquals(LEGACY_ATTACHMENT_CONTENT,
                payload.resource1.contentStream().readAllBytes());
    }

    @Test
    protected void compatOnReadsTheLargeCaptureByteExact() throws IOException {
        WireFixture fixture = legacyFixture("message-data.http", LEGACY_BOUNDARY);
        // The fixture itself is the authority: its attachment content is
        // everything between the part's blank line and the closing delimiter
        byte[] expected = attachmentSlice(fixture.body(), LEGACY_BOUNDARY);
        assertTrue(expected.length > 1_000_000, "capture must be the large variant");

        LegacyPayload payload = read(true, fixture).payload();
        byte[] received = payload.resource1.contentStream().readAllBytes();

        assertEquals(expected.length, received.length);
        assertArrayEquals(expected, received);
    }

    @Test
    protected void unknownAttachmentIdCaptureYieldsUnavailableNotHang() {
        WireFixture fixture = legacyFixture("message-bad-attachment_id.http", LEGACY_BOUNDARY);
        LegacyPayload payload = read(true, fixture).payload();
        assertNotNull(payload.resource1);
        // The referenced part never arrives (the wire part has a different
        // id and is leniently skipped); reading must fail typed, promptly
        RestxopException error = assertThrows(RestxopException.class,
                () -> payload.resource1.contentStream().readAllBytes());
        assertInstanceOf(AttachmentUnavailableException.class, error);
    }

    @Test
    protected void badJsonCaptureFailsTyped() {
        WireFixture fixture = legacyFixture("message-bad-json.http", LEGACY_BOUNDARY);
        assertThrows(MalformedMessageException.class, () -> read(true, fixture));
    }

    @Test
    protected void truncatedCaptureFailsTyped() {
        WireFixture fixture = legacyFixture("message-partial.http", LEGACY_BOUNDARY);
        ReadResult<LegacyPayload> result = read(true, fixture);
        assertThrows(RestxopException.class,
                () -> result.payload().resource1.contentStream().readAllBytes());
    }

    // ------------------------------------------------------------------
    // Compat write mode (wire-format §7)
    // ------------------------------------------------------------------

    private record Written(String contentType, String responseId, byte[] body) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Written that
                    && contentType.equals(that.contentType)
                    && responseId.equals(that.responseId)
                    && java.util.Arrays.equals(body, that.body);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(contentType, responseId, java.util.Arrays.hashCode(body));
        }

        @Override
        public String toString() {
            return "Written[contentType=" + contentType + ", responseId=" + responseId
                    + ", body=" + body.length + " bytes]";
        }
    }

    private Written writeCompat(LegacyPayload payload) throws IOException {
        AtomicInteger next = new AtomicInteger();
        MessageWriter writer = new MessageWriter(config(true), codec(),
                new MessageWriter.WriterIds("legacy-fixture-boundary", "ignored-root-id",
                        () -> "aaaaaaaa-0000-0000-0000-00000000000" + next.incrementAndGet()));
        Exchange exchange = Exchange.open(config(true), List.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(exchange, payload, out);
        exchange.complete();
        return new Written(writer.contentType(),
                writer.responseIdHeader().orElse(null), out.toByteArray());
    }

    @Test
    protected void compatWriteEmitsTheLegacyShapes() throws IOException {
        LegacyPayload payload = new LegacyPayload("String Value",
                Attachment.builder("This is a test".getBytes(StandardCharsets.ISO_8859_1))
                        .filename("Test-123")
                        .contentType("application/octet-stream")
                        .build());

        Written written = writeCompat(payload);
        String body = new String(written.body(), StandardCharsets.ISO_8859_1);

        assertEquals("composite/related; type=\"application/json\"; "
                + "boundary=\"legacy-fixture-boundary\"; start=\"<mainpart>\"",
                written.contentType(), "legacy outer media type and parameters");
        assertNotNull(written.responseId(), "legacy Response-ID header value must be exposed");
        assertTrue(body.contains("Content-ID: <mainpart>\r\n"), "legacy root id:\n" + body);
        assertTrue(body.contains("Content-ID: aaaaaaaa-0000-0000-0000-000000000001\r\n"),
                "attachment Content-ID must be a bare (unbracketed) id:\n" + body);
        assertTrue(body.contains("\"href\":\"aaaaaaaa-0000-0000-0000-000000000001\""),
                "href must be the bare id without the cid: prefix:\n" + body);
        assertTrue(body.contains("Content-Disposition: attachment;name=\"Test-123\"\r\n"),
                "legacy disposition shape (name parameter, no space):\n" + body);
    }

    @Test
    protected void compatWriteRoundTripsThroughTheCompatReader() throws IOException {
        byte[] content = new byte[300_000];
        new java.util.Random(12).nextBytes(content);
        LegacyPayload outgoing = new LegacyPayload("round trip",
                Attachment.builder(content).filename("legacy.bin").build());

        Written written = writeCompat(outgoing);
        LegacyPayload incoming = reader(true).<LegacyPayload>read(written.contentType(),
                new ByteArrayInputStream(written.body()),
                ResolvableTypeInfo.of(LegacyPayload.class), null).payload();

        assertEquals("round trip", incoming.field1);
        assertArrayEquals(content, incoming.resource1.contentStream().readAllBytes());
        assertEquals("legacy.bin", incoming.resource1.filename().orElseThrow());
    }

    @Test
    protected void standardWritesAreUntouchedWhenCompatIsOff() throws IOException {
        MessageWriter writer = new MessageWriter(config(false), codec(),
                new MessageWriter.WriterIds("std-b", "root", () -> "att-1"));
        Exchange exchange = Exchange.open(config(false), List.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(exchange, new LegacyPayload("std",
                Attachment.of("x".getBytes(StandardCharsets.UTF_8))), out);
        exchange.complete();

        assertTrue(writer.contentType().startsWith("multipart/related;"));
        assertTrue(writer.responseIdHeader().isEmpty());
        String body = out.toString(StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("Content-ID: <att-1>\r\n"), "bracketed id in standard mode");
        assertTrue(body.contains("\"href\":\"cid:att-1\""), "cid: href in standard mode");
    }

    /** Extracts the (single) attachment part's exact content bytes from a legacy capture. */
    protected static byte[] attachmentSlice(byte[] body, String boundary) {
        byte[] delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int secondDelimiter = indexOf(body, delimiter, indexOf(body, delimiter, 0) + 1);
        byte[] blank = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        int contentStart = indexOf(body, blank, secondDelimiter) + blank.length;
        int closing = indexOf(body, delimiter, contentStart);
        return Arrays.copyOfRange(body, contentStart, closing);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        for (int i = Math.max(from, 0); i <= haystack.length - needle.length; i++) {
            if (matchesAt(haystack, i, needle)) {
                return i;
            }
        }
        throw new IllegalStateException("marker not found in capture");
    }

    private static boolean matchesAt(byte[] haystack, int at, byte[] needle) {
        for (int j = 0; j < needle.length; j++) {
            if (haystack[at + j] != needle[j]) {
                return false;
            }
        }
        return true;
    }
}
