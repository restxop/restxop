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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.restxop.Attachment;
import dev.restxop.RestxopConfig;
import dev.restxop.core.internal.exchange.Exchange;
import dev.restxop.spi.AttachmentCollector;
import dev.restxop.spi.AttachmentResolver;
import dev.restxop.spi.ResolvableTypeInfo;
import dev.restxop.spi.RootPartCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class MessageWriterTest {

    private static final String BOUNDARY = "rx-fixture-0001";

    /** Loads a canonical fixture from the testkit resources (shared on the test classpath). */
    private static byte[] fixtureBody(String name) throws IOException {
        Path path = Path.of("../restxop-testkit/src/main/resources/fixtures/canonical/" + name);
        byte[] raw = Files.readAllBytes(path);
        for (int i = 0; i < raw.length - 3; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                byte[] body = new byte[raw.length - (i + 4)];
                System.arraycopy(raw, i + 4, body, 0, body.length);
                return body;
            }
        }
        throw new IllegalStateException("no header/body separator in " + name);
    }

    private static MessageWriter.WriterIds fixtureIds() {
        AtomicInteger next = new AtomicInteger();
        return new MessageWriter.WriterIds(BOUNDARY, "root",
                () -> "att-" + next.incrementAndGet());
    }

    /** Codec stub emitting the canonical single-attachment JSON shape. */
    private record SinglePayload(String title, Attachment report) {
    }

    private static final RootPartCodec SINGLE_CODEC = new RootPartCodec() {
        @Override
        public boolean canHandle(ResolvableTypeInfo type) {
            return true;
        }

        @Override
        public void writeRoot(Object payload, OutputStream out, AttachmentCollector collector) {
            SinglePayload p = (SinglePayload) payload;
            try {
                String reportJson = p.report() == null ? "null"
                        : "{\"Include\":{\"href\":\"cid:" + collector.register(p.report()) + "\"}}";
                out.write(("{\"title\":\"" + p.title() + "\",\"report\":" + reportJson + "}")
                        .getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Object readRoot(InputStream in, ResolvableTypeInfo type, AttachmentResolver resolver) {
            throw new UnsupportedOperationException();
        }
    };

    private static byte[] write(RootPartCodec codec, Object payload) throws IOException {
        RestxopConfig config = RestxopConfig.defaults();
        Exchange exchange = Exchange.open(config, List.of());
        MessageWriter writer = new MessageWriter(config, codec, fixtureIds());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writer.write(exchange, payload, out);
            exchange.complete();
        } catch (Exception e) {
            exchange.fail(e);
            throw e;
        }
        return out.toByteArray();
    }

    @Test
    void singleAttachmentMessageMatchesCanonicalFixtureByteForByte() throws IOException {
        byte[] content = ("first line\r\n--rx-fixture-0001 almost a delimiter\r\n"
                + "binary \u0000\u0001\u0002 bytes with -- dashes").getBytes(StandardCharsets.ISO_8859_1);
        SinglePayload payload = new SinglePayload("Quarterly report",
                Attachment.builder(content)
                        .filename("data.bin")
                        .contentType("application/octet-stream")
                        .build());

        byte[] body = write(SINGLE_CODEC, payload);

        assertArrayEquals(fixtureBody("single-attachment.http"), body);
    }

    @Test
    void nullAttachmentEmitsRootOnlyMessageMatchingFixture() throws IOException {
        byte[] fixture = fixtureBody("null-attachment.http");
        byte[] body = write(SINGLE_CODEC, new SinglePayload("no report", null));
        assertArrayEquals(fixture, body);
    }

    @Test
    void contentTypeHeaderIsCanonicallyQuoted() {
        MessageWriter writer = new MessageWriter(RestxopConfig.defaults(), SINGLE_CODEC, fixtureIds());
        assertEquals("multipart/related; type=\"application/json\"; "
                + "boundary=\"rx-fixture-0001\"; start=\"<root>\"", writer.contentType());
    }

    @Test
    void dispositionFilenameIsQuotedWithEscapes() throws IOException {
        SinglePayload payload = new SinglePayload("Quarterly report",
                Attachment.builder("x".getBytes(StandardCharsets.UTF_8))
                        .filename("we\"ird\\name.bin")
                        .contentType("text/plain")
                        .build());

        String body = new String(write(SINGLE_CODEC, payload), StandardCharsets.ISO_8859_1);

        assertTrue(body.contains("Content-Disposition: attachment; filename=\"we\\\"ird\\\\name.bin\""),
                body);
        assertTrue(body.contains("Content-ID: <att-1>"), "attachment Content-ID must be bracketed");
        assertTrue(body.contains("Content-Type: text/plain"), body);
        assertTrue(body.contains("Content-Transfer-Encoding: binary"), body);
    }

    @Test
    void attachmentWithoutContentTypeDefaultsToOctetStream() throws IOException {
        SinglePayload payload = new SinglePayload("Quarterly report",
                Attachment.of("data".getBytes(StandardCharsets.UTF_8)));
        String body = new String(write(SINGLE_CODEC, payload), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("Content-Type: application/octet-stream"), body);
    }

    @Test
    void streamedSourceIsNeverFullyBuffered() throws IOException {
        int totalSize = 4 * 1024 * 1024;
        AtomicLong sourceRead = new AtomicLong();
        AtomicLong sinkWritten = new AtomicLong();
        int allowedInFlight = 512 * 1024;

        InputStream source = new InputStream() {
            private long produced;

            @Override
            public int read() {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (produced >= totalSize) {
                    return -1;
                }
                long inFlight = sourceRead.get() - sinkWritten.get();
                assertTrue(inFlight <= allowedInFlight,
                        "writer buffered " + inFlight + " bytes of the source");
                int n = (int) Math.min(len, totalSize - produced);
                java.util.Arrays.fill(b, off, off + n, (byte) 'a');
                produced += n;
                sourceRead.addAndGet(n);
                return n;
            }
        };

        OutputStream sink = new OutputStream() {
            @Override
            public void write(int b) {
                sinkWritten.incrementAndGet();
            }

            @Override
            public void write(byte[] b, int off, int len) {
                sinkWritten.addAndGet(len);
            }
        };

        SinglePayload payload = new SinglePayload("big", Attachment.of(source));
        Exchange exchange = Exchange.open(RestxopConfig.defaults(), List.of());
        new MessageWriter(RestxopConfig.defaults(), SINGLE_CODEC, fixtureIds())
                .write(exchange, payload, sink);
        exchange.complete();

        assertEquals(totalSize, sourceRead.get(), "entire source must be streamed");
    }

    @Test
    void multipleAttachmentsEmitPartsInEncounterOrder() throws IOException {
        record MultiPayload(Attachment first, Attachment second) {
        }
        RootPartCodec codec = new RootPartCodec() {
            @Override
            public boolean canHandle(ResolvableTypeInfo type) {
                return true;
            }

            @Override
            public void writeRoot(Object payload, OutputStream out, AttachmentCollector collector) {
                MultiPayload p = (MultiPayload) payload;
                try {
                    out.write(("{\"a\":\"cid:" + collector.register(p.first())
                            + "\",\"b\":\"cid:" + collector.register(p.second()) + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public Object readRoot(InputStream in, ResolvableTypeInfo type, AttachmentResolver resolver) {
                throw new UnsupportedOperationException();
            }
        };

        MultiPayload payload = new MultiPayload(
                Attachment.of(new ByteArrayInputStream("first-content".getBytes(StandardCharsets.UTF_8))),
                Attachment.of(new ByteArrayInputStream("second-content".getBytes(StandardCharsets.UTF_8))));
        Exchange exchange = Exchange.open(RestxopConfig.defaults(), List.of());
        MessageWriter writer = new MessageWriter(RestxopConfig.defaults(), codec, fixtureIds());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(exchange, payload, out);
        exchange.complete();

        String body = out.toString(StandardCharsets.ISO_8859_1);
        List<Integer> positions = new ArrayList<>(List.of(
                body.indexOf("Content-ID: <att-1>"),
                body.indexOf("first-content"),
                body.indexOf("Content-ID: <att-2>"),
                body.indexOf("second-content")));
        for (int i = 0; i < positions.size(); i++) {
            assertTrue(positions.get(i) >= 0, "segment " + i + " missing:\n" + body);
            if (i > 0) {
                assertTrue(positions.get(i) > positions.get(i - 1),
                        "parts must appear in encounter order:\n" + body);
            }
        }
        assertTrue(body.endsWith("\r\n--" + BOUNDARY + "--\r\n"), "closing delimiter required");
    }
}
