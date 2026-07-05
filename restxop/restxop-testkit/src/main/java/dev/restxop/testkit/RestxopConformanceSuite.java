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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.restxop.Attachment;
import dev.restxop.testkit.model.BundlePayload;
import dev.restxop.testkit.model.NestedPayload;
import dev.restxop.testkit.model.PlainPayload;
import dev.restxop.testkit.model.ReportPayload;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Abstract wire-level conformance suite. Each starter's test module (and any
 * adopter with custom SPI implementations) extends this class and implements
 * the two codec hooks; the inherited tests then assert identical behavior
 * against the canonical fixtures byte-for-byte, which is how cross-generation
 * wire identity (SC-005) is proven.
 */
@Timeout(60)
public abstract class RestxopConformanceSuite {

    /** Canonical fixture names bundled with the testkit. */
    protected static final String SINGLE_ATTACHMENT = "canonical/single-attachment.http";
    protected static final String MULTI_ATTACHMENT = "canonical/multi-attachment.http";
    protected static final String NESTED_ATTACHMENT = "canonical/nested-attachment.http";
    protected static final String NULL_ATTACHMENT = "canonical/null-attachment.http";
    protected static final String ZERO_ATTACHMENT = "canonical/zero-attachment.http";

    /** The fixtures' fixed identifier scheme. */
    protected static final String FIXTURE_BOUNDARY = "rx-fixture-0001";
    protected static final String FIXTURE_ROOT_ID = "root";

    /** Deterministic identifier settings for fixture-comparable output. */
    public record WriterSettings(String boundary, String rootContentId,
            List<String> attachmentContentIds) {

        public static WriterSettings fixture() {
            return new WriterSettings(FIXTURE_BOUNDARY, FIXTURE_ROOT_ID,
                    List.of("att-1", "att-2", "att-3", "att-4",
                            "att-5", "att-6", "att-7", "att-8"));
        }
    }

    /** A produced wire message: outer Content-Type plus exact body bytes. */
    public record EncodedMessage(String contentType, byte[] body) {
    }

    /**
     * Serializes {@code payload} to a wire message through the
     * implementation under test using the given deterministic identifiers.
     */
    protected abstract EncodedMessage encode(Object payload, WriterSettings settings);

    /**
     * Deserializes a wire message through the implementation under test into
     * a payload of {@code type}; returned attachments must be lazily
     * readable per the read-path contract.
     */
    protected abstract <T> T decode(String contentType, InputStream body, Type type);

    /** Loads a canonical fixture by name. */
    protected static WireFixture fixture(String name) {
        return Fixtures.load(name);
    }

    private <T> T decode(WireFixture fixture, Type type) {
        return decode(fixture.contentType(), new ByteArrayInputStream(fixture.body()), type);
    }

    // ---------------------------------------------------------------------
    // Fixture content (mirrors the generated canonical fixtures exactly)
    // ---------------------------------------------------------------------

    protected static byte[] singleAttachmentContent() {
        return ("first line\r\n--rx-fixture-0001 almost a delimiter\r\n"
                + "binary \u0000\u0001\u0002 bytes with -- dashes")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] alphaContent() {
        return "alpha content".getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] pdfContent() {
        return "%PDF-1.7 fake minimal content".getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] nestedContent() {
        return "nested-bytes".getBytes(StandardCharsets.ISO_8859_1);
    }

    // ---------------------------------------------------------------------
    // US1: write path against canonical fixtures, byte-for-byte
    // ---------------------------------------------------------------------

    @Test
    protected void writesSingleAttachmentMessageByteIdenticalToFixture() {
        ReportPayload payload = new ReportPayload("Quarterly report",
                Attachment.builder(singleAttachmentContent())
                        .filename("data.bin")
                        .contentType("application/octet-stream")
                        .build());
        WireFixture fixture = fixture(SINGLE_ATTACHMENT);

        EncodedMessage message = encode(payload, WriterSettings.fixture());

        assertEquals(fixture.contentType(), message.contentType());
        assertArrayEquals(fixture.body(), message.body());
    }

    @Test
    protected void writesMultiAttachmentMessageByteIdenticalToFixture() {
        BundlePayload payload = new BundlePayload("bundle",
                Attachment.builder(alphaContent())
                        .filename("alpha.txt").contentType("text/plain").build(),
                Attachment.builder(pdfContent())
                        .filename("report.pdf").contentType("application/pdf").build());

        EncodedMessage message = encode(payload, WriterSettings.fixture());

        assertArrayEquals(fixture(MULTI_ATTACHMENT).body(), message.body());
    }

    @Test
    protected void writesNestedAttachmentWithoutDispositionByteIdenticalToFixture() {
        NestedPayload payload = new NestedPayload("nested", new NestedPayload.Inner(
                Attachment.builder(nestedContent())
                        .contentType("application/octet-stream").build()));

        EncodedMessage message = encode(payload, WriterSettings.fixture());

        assertArrayEquals(fixture(NESTED_ATTACHMENT).body(), message.body());
    }

    @Test
    protected void writesNullAttachmentAsRootOnlyMessage() {
        EncodedMessage message = encode(new ReportPayload("no report", null),
                WriterSettings.fixture());
        assertArrayEquals(fixture(NULL_ATTACHMENT).body(), message.body());
    }

    @Test
    protected void writesZeroAttachmentPayloadAsRootOnlyMessage() {
        EncodedMessage message = encode(new PlainPayload("plain", 42), WriterSettings.fixture());
        assertArrayEquals(fixture(ZERO_ATTACHMENT).body(), message.body());
    }

    // ---------------------------------------------------------------------
    // US1: read path from canonical fixtures
    // ---------------------------------------------------------------------

    @Test
    protected void readsSingleAttachmentFixtureByteExactWithMetadata() throws IOException {
        ReportPayload payload = decode(fixture(SINGLE_ATTACHMENT), ReportPayload.class);

        assertEquals("Quarterly report", payload.title);
        assertNotNull(payload.report);
        assertArrayEquals(singleAttachmentContent(), payload.report.contentStream().readAllBytes());
        assertEquals("data.bin", payload.report.filename().orElseThrow());
        assertEquals("application/octet-stream", payload.report.contentType().orElseThrow());
    }

    @Test
    protected void readsMultiAttachmentFixtureByteExact() throws IOException {
        BundlePayload payload = decode(fixture(MULTI_ATTACHMENT), BundlePayload.class);

        assertEquals("bundle", payload.name);
        assertArrayEquals(alphaContent(), payload.first.contentStream().readAllBytes());
        assertArrayEquals(pdfContent(), payload.second.contentStream().readAllBytes());
        assertEquals("alpha.txt", payload.first.filename().orElseThrow());
        assertEquals("report.pdf", payload.second.filename().orElseThrow());
        assertEquals("text/plain", payload.first.contentType().orElseThrow());
        assertEquals("application/pdf", payload.second.contentType().orElseThrow());
    }

    @Test
    protected void readsNestedAttachmentFixture() throws IOException {
        NestedPayload payload = decode(fixture(NESTED_ATTACHMENT), NestedPayload.class);

        assertEquals("nested", payload.label);
        assertArrayEquals(nestedContent(), payload.inner.data.contentStream().readAllBytes());
        assertTrue(payload.inner.data.filename().isEmpty(), "no Disposition, no filename");
    }

    @Test
    protected void readsNullAttachmentFixtureToNullField() {
        ReportPayload payload = decode(fixture(NULL_ATTACHMENT), ReportPayload.class);
        assertEquals("no report", payload.title);
        assertNull(payload.report);
    }

    @Test
    protected void readsZeroAttachmentFixtureImmediately() {
        PlainPayload payload = decode(fixture(ZERO_ATTACHMENT), PlainPayload.class);
        assertEquals("plain", payload.message);
        assertEquals(42, payload.number);
    }

    // ---------------------------------------------------------------------
    // US1: same-implementation round trip beyond the memory window
    // ---------------------------------------------------------------------

    @Test
    protected void roundTripsLargeContentChecksumExact() throws Exception {
        byte[] content = new byte[1_500_000];
        new Random(99).nextBytes(content);
        ReportPayload outgoing = new ReportPayload("big",
                Attachment.builder(content).filename("big.bin").build());

        EncodedMessage message = encode(outgoing, WriterSettings.fixture());
        ReportPayload incoming = decode(message.contentType(),
                new ByteArrayInputStream(message.body()), ReportPayload.class);

        byte[] received = incoming.report.contentStream().readAllBytes();
        assertEquals(content.length, received.length);
        assertArrayEquals(checksum(content), checksum(received));
    }

    @Test
    protected void duplicateReferencesTransmitOnePartAndShareOneInstance() throws IOException {
        Attachment shared = Attachment.builder("shared bytes".getBytes(StandardCharsets.UTF_8))
                .contentType("application/octet-stream").build();
        BundlePayload outgoing = new BundlePayload("dup", shared, shared);

        EncodedMessage message = encode(outgoing, WriterSettings.fixture());
        String text = new String(message.body(), StandardCharsets.ISO_8859_1);
        assertEquals(1, countOccurrences(text, "Content-ID: <att-1>"),
                "exactly one part for the duplicated instance");
        assertEquals(0, countOccurrences(text, "Content-ID: <att-2>"));

        BundlePayload incoming = decode(message.contentType(),
                new ByteArrayInputStream(message.body()), BundlePayload.class);
        assertSame(incoming.first, incoming.second, "one shared instance on read (FR-012)");
        assertArrayEquals("shared bytes".getBytes(StandardCharsets.UTF_8),
                incoming.first.contentStream().readAllBytes());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static byte[] checksum(byte[] data) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }
}
