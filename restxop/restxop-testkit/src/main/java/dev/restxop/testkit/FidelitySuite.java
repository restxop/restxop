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
import dev.restxop.testkit.RestxopConformanceSuite.EncodedMessage;
import dev.restxop.testkit.RestxopConformanceSuite.WriterSettings;
import dev.restxop.testkit.model.BundlePayload;
import dev.restxop.testkit.model.NestedPayload;
import dev.restxop.testkit.model.PlainPayload;
import dev.restxop.testkit.model.ReportPayload;
import dev.restxop.testkit.model.RichPayload;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * SC-003 fidelity suite (tag {@code fidelity}): rich object graphs
 * (nested, collections, maps, inherited, null), duplicate-reference
 * dedup, out-of-order consumption across the spill crossover, and
 * filename/content-type carriage including RFC 6266 non-ASCII names —
 * every transfer checksum-verified.
 */
@Tag("fidelity")
@Timeout(120)
public abstract class FidelitySuite {

    /** Serializes through the implementation under test (deterministic ids). */
    protected abstract EncodedMessage encode(Object payload, WriterSettings settings);

    /** Deserializes through the implementation under test. */
    protected abstract <T> T decode(String contentType, InputStream body, Type type);

    private <T> T decode(EncodedMessage message, Type type) {
        return decode(message.contentType(), new ByteArrayInputStream(message.body()), type);
    }

    // Deterministic seeded content is the point: fixtures must be
    // byte-reproducible across suite runs and implementations (not security)
    @SuppressWarnings("java:S2245")
    private static byte[] content(int seed, int size) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static byte[] sha(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int count(byte[] haystack, String needle) {
        String text = new String(haystack, StandardCharsets.ISO_8859_1);
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Rich graphs: nesting, collections, maps, inheritance, null
    // ------------------------------------------------------------------

    @Test
    protected void richGraphRoundTripsEveryAttachmentChecksumExact() throws IOException {
        byte[] inheritedBytes = content(1, 64 * 1024);
        byte[] nestedBytes = content(2, 48 * 1024);
        byte[] itemA = content(3, 10_000);
        byte[] itemB = content(4, 20_000);
        byte[] mapped = content(5, 30_000);

        RichPayload outgoing = new RichPayload();
        outgoing.label = "rich";
        outgoing.inherited = Attachment.of(inheritedBytes);
        outgoing.inner = new NestedPayload.Inner(Attachment.of(nestedBytes));
        outgoing.items = List.of(Attachment.of(itemA), Attachment.of(itemB));
        outgoing.byName = new LinkedHashMap<>(Map.of("only", Attachment.of(mapped)));
        outgoing.missing = null;

        EncodedMessage message = encode(outgoing, WriterSettings.fixture());
        assertEquals(6, count(message.body(), "Content-ID:"),
                "root + five attachment parts, none for the null field");

        RichPayload incoming = decode(message, RichPayload.class);

        assertEquals("rich", incoming.label);
        assertArrayEquals(inheritedBytes, incoming.inherited.contentStream().readAllBytes(),
                "inherited field");
        assertArrayEquals(nestedBytes, incoming.inner.data.contentStream().readAllBytes(),
                "nested field");
        assertEquals(2, incoming.items.size());
        assertArrayEquals(itemA, incoming.items.get(0).contentStream().readAllBytes(), "list[0]");
        assertArrayEquals(itemB, incoming.items.get(1).contentStream().readAllBytes(), "list[1]");
        assertArrayEquals(mapped, incoming.byName.get("only").contentStream().readAllBytes(),
                "map value");
        assertNull(incoming.missing, "null attachment round-trips as null");
    }

    @Test
    protected void nullFieldEmitsNoPartAndZeroAttachmentPayloadIsImmediate() {
        EncodedMessage nullMessage = encode(new ReportPayload("no report", null),
                WriterSettings.fixture());
        assertEquals(1, count(nullMessage.body(), "Content-ID:"), "root part only");
        ReportPayload nullIncoming = decode(nullMessage, ReportPayload.class);
        assertEquals("no report", nullIncoming.title);
        assertNull(nullIncoming.report);

        long start = System.nanoTime();
        PlainPayload plain = decode(encode(new PlainPayload("now", 7), WriterSettings.fixture()),
                PlainPayload.class);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertEquals("now", plain.message);
        assertTrue(elapsedMillis < 2000, "zero-attachment read returns without delay");
    }

    @Test
    protected void duplicateReferenceIsOnePartAndOneSharedInstance() throws IOException {
        byte[] bytes = content(6, 32 * 1024);
        Attachment shared = Attachment.of(bytes);
        EncodedMessage message = encode(new BundlePayload("dup", shared, shared),
                WriterSettings.fixture());

        assertEquals(2, count(message.body(), "Content-ID:"), "root + exactly one shared part");

        BundlePayload incoming = decode(message, BundlePayload.class);
        assertSame(incoming.first, incoming.second, "same instance for the same Content-ID");
        assertArrayEquals(sha(bytes), sha(incoming.first.contentStream().readAllBytes()));
    }

    // ------------------------------------------------------------------
    // Out-of-order consumption across the spill crossover
    // ------------------------------------------------------------------

    @Test
    protected void outOfOrderReadsCrossTheSpillBoundaryChecksumExact() throws IOException {
        // Both attachments exceed the default 256 KiB memory window, so
        // reading the second first forces the first through the overflow
        byte[] first = content(7, 1_000_000);
        byte[] second = content(8, 1_100_000);
        EncodedMessage message = encode(new BundlePayload("ooo",
                        Attachment.of(first), Attachment.of(second)),
                WriterSettings.fixture());

        BundlePayload incoming = decode(message, BundlePayload.class);

        byte[] receivedSecond = incoming.second.contentStream().readAllBytes();
        byte[] receivedFirst = incoming.first.contentStream().readAllBytes();

        assertArrayEquals(sha(second), sha(receivedSecond), "second (read first)");
        assertArrayEquals(sha(first), sha(receivedFirst), "first (read after drain completion)");
        assertEquals(first.length, receivedFirst.length);
        assertEquals(second.length, receivedSecond.length);
    }

    @Test
    protected void zeroByteAttachmentRoundTrips() throws IOException {
        EncodedMessage message = encode(new ReportPayload("empty", Attachment.of(new byte[0])),
                WriterSettings.fixture());
        assertEquals(2, count(message.body(), "Content-ID:"), "empty part still transmitted");
        ReportPayload incoming = decode(message, ReportPayload.class);
        assertNotNull(incoming.report);
        assertArrayEquals(new byte[0], incoming.report.contentStream().readAllBytes());
    }

    // ------------------------------------------------------------------
    // Metadata carriage (FR-004, FR-019, RFC 6266)
    // ------------------------------------------------------------------

    @Test
    protected void metadataIsExposedBeforeTheContentIsRead() {
        byte[] bytes = content(9, 64 * 1024);
        ReportPayload outgoing = new ReportPayload("meta",
                Attachment.builder(bytes)
                        .filename("quarterly report.pdf")
                        .contentType("application/pdf")
                        .build());
        EncodedMessage message = encode(outgoing, WriterSettings.fixture());

        // Delay the attachment part on the wire: metadata exposure must be
        // a bounded wait for the part headers, not a race with the drain
        int gate = indexOf(message.body(), "Content-ID: <att-1>");
        ReportPayload incoming = decode(message.contentType(),
                new DelayedInputStream(message.body(), gate, 300), ReportPayload.class);

        assertEquals("quarterly report.pdf", incoming.report.filename().orElseThrow(
                () -> new AssertionError("filename must be available before reading content")));
        assertEquals("application/pdf", incoming.report.contentType().orElseThrow());
    }

    @Test
    protected void asciiFilenameAndContentTypeRoundTrip() throws IOException {
        ReportPayload outgoing = new ReportPayload("named",
                Attachment.builder(content(10, 1024))
                        .filename("plain name.bin")
                        .contentType("application/x-custom")
                        .build());

        ReportPayload incoming = decode(encode(outgoing, WriterSettings.fixture()),
                ReportPayload.class);
        incoming.report.contentStream().readAllBytes();

        assertEquals("plain name.bin", incoming.report.filename().orElseThrow());
        assertEquals("application/x-custom", incoming.report.contentType().orElseThrow());
    }

    @Test
    protected void nonAsciiFilenameRoundTripsViaRfc6266() throws IOException {
        String filename = "naïve – 文件.pdf";
        ReportPayload outgoing = new ReportPayload("intl",
                Attachment.builder(content(11, 2048))
                        .filename(filename)
                        .contentType("application/pdf")
                        .build());

        EncodedMessage message = encode(outgoing, WriterSettings.fixture());
        String text = new String(message.body(), StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("filename*=UTF-8''na%C3%AFve%20%E2%80%93%20%E6%96%87%E4%BB%B6.pdf"),
                "non-ASCII names must travel percent-encoded via filename* (wire-format §3):\n"
                        + text);

        ReportPayload incoming = decode(message, ReportPayload.class);
        incoming.report.contentStream().readAllBytes();
        assertEquals(filename, incoming.report.filename().orElseThrow());
    }

    @Test
    protected void readsTheCapturedNonAsciiFilenameFixture() throws IOException {
        WireFixture fixture = Fixtures.load("fidelity/nonascii-filename.http");
        ReportPayload incoming = decode(fixture.contentType(),
                new ByteArrayInputStream(fixture.body()), ReportPayload.class);

        byte[] received = incoming.report.contentStream().readAllBytes();
        assertArrayEquals("pdf content bytes for the rfc6266 fixture"
                .getBytes(StandardCharsets.ISO_8859_1), received);
        assertEquals("naïve – 文件.pdf", incoming.report.filename().orElseThrow());
        assertEquals("application/pdf", incoming.report.contentType().orElseThrow());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    protected static int indexOf(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new IllegalStateException("marker not found: " + needle);
    }

    /** Serves bytes up to a gate, sleeps once, then serves the rest. */
    protected static final class DelayedInputStream extends InputStream {

        private final byte[] data;
        private final int gate;
        private final long delayMillis;
        private int position;
        private boolean delayed;

        DelayedInputStream(byte[] data, int gate, long delayMillis) {
            this.data = data;
            this.gate = gate;
            this.delayMillis = delayMillis;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (position >= data.length) {
                return -1;
            }
            if (position >= gate && !delayed) {
                delayed = true;
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted during delay", e);
                }
            }
            int bound = delayed ? data.length : Math.min(gate, data.length);
            int n = Math.min(len, bound - position);
            if (n <= 0) {
                return read(b, off, len);
            }
            System.arraycopy(data, position, b, off, n);
            position += n;
            return n;
        }
    }
}
