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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.restxop.Attachment;
import dev.restxop.RestxopConfig;
import dev.restxop.core.internal.buffer.FileSpoolStorage;
import dev.restxop.core.internal.exchange.Exchange;
import dev.restxop.spi.AttachmentCollector;
import dev.restxop.spi.AttachmentInfo;
import dev.restxop.spi.AttachmentResolver;
import dev.restxop.spi.ExchangeInfo;
import dev.restxop.spi.ExchangeListener;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
class MessageReaderTest {

    private static final String BOUNDARY = "test-boundary-4711";
    private static final String CONTENT_TYPE = "multipart/related; type=\"application/json\"; "
            + "boundary=\"" + BOUNDARY + "\"; start=\"<root>\"";

    private MessageReader reader;

    @AfterEach
    void shutdownReader() {
        if (reader != null) {
            reader.close();
        }
    }

    /** Payload produced by the stub codec: raw root JSON + resolved attachments in href order. */
    record TestPayload(String json, List<Attachment> attachments) {
    }

    private static final Pattern HREF = Pattern.compile("\"cid:([^\"]+)\"");

    private static final RootPartCodec STUB_CODEC = new RootPartCodec() {
        @Override
        public boolean canHandle(ResolvableTypeInfo type) {
            return true;
        }

        @Override
        public void writeRoot(Object payload, OutputStream out, AttachmentCollector collector) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object readRoot(InputStream in, ResolvableTypeInfo type, AttachmentResolver resolver) {
            try {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                List<Attachment> attachments = new ArrayList<>();
                Matcher matcher = HREF.matcher(json);
                while (matcher.find()) {
                    attachments.add(resolver.resolve(matcher.group(1)));
                }
                return new TestPayload(json, attachments);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    };

    /** Builds a CRLF-framed message with a root referencing the given parts. */
    private static byte[] message(List<String> contentIds, List<byte[]> contents) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder json = new StringBuilder("{\"refs\":[");
        for (int i = 0; i < contentIds.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("\"cid:").append(contentIds.get(i)).append('"');
        }
        json.append("]}");
        try {
            writePart(out, "root", "application/json",
                    json.toString().getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < contentIds.size(); i++) {
                writePart(out, contentIds.get(i), "application/octet-stream", contents.get(i));
            }
            out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void writePart(ByteArrayOutputStream out, String cid, String type, byte[] content)
            throws IOException {
        out.write(("\r\n--" + BOUNDARY + "\r\n"
                + "Content-ID: <" + cid + ">\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Transfer-Encoding: binary\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(content);
    }

    private MessageReader newReader(RestxopConfig config, ExchangeListener... listeners) {
        reader = new MessageReader(config, STUB_CODEC, new FileSpoolStorage(), List.of(listeners));
        return reader;
    }

    private static ResolvableTypeInfo payloadType() {
        return ResolvableTypeInfo.of(TestPayload.class);
    }

    @Test
    void payloadIsReturnedWhileDrainIsStillRunning() throws Exception {
        byte[] content = new byte[64 * 1024];
        new Random(1).nextBytes(content);
        byte[] body = message(List.of("att-1"), List.of(content));
        // Gate the transport just after the attachment part's headers: the
        // payload must arrive without the attachment bytes
        int gateAt = indexOf(body, "Content-ID: <att-1>".getBytes(StandardCharsets.ISO_8859_1));
        GatedInputStream transport = new GatedInputStream(body, gateAt + 40_000 < body.length
                ? gateAt + 200 : gateAt);

        MessageReader r = newReader(RestxopConfig.defaults());
        ReadResult<TestPayload> result = r.read(CONTENT_TYPE,
                transport, payloadType(), null);

        assertTrue(result.payload().json().contains("cid:att-1"),
                "payload must be delivered before the drain finishes");
        assertEquals(Exchange.State.OPEN, result.exchange().state(),
                "exchange still open while attachment is in flight");

        transport.open();
        byte[] received = result.payload().attachments().get(0).contentStream().readAllBytes();
        assertArrayEquals(content, received);
        awaitState(result.exchange(), Exchange.State.COMPLETED);
    }

    @Test
    void attachmentIsChecksumExactAcrossSpillCrossover(@TempDir Path spoolDir) throws Exception {
        byte[] content = new byte[2 * 1024 * 1024];
        new Random(2).nextBytes(content);
        RestxopConfig config = RestxopConfig.builder()
                .memoryWindowPerPart(8 * 1024)
                .spoolDirectory(spoolDir)
                .build();
        AtomicLong spooled = new AtomicLong();
        MessageReader r = newReader(config, new ExchangeListener() {
            @Override
            public void bytesSpooled(ExchangeInfo info, AttachmentInfo att, long total) {
                spooled.set(total);
            }
        });

        ReadResult<TestPayload> result = r.read(CONTENT_TYPE,
                new ByteArrayInputStream(message(List.of("att-1"), List.of(content))),
                payloadType(), null);

        // Let the drain finish completely before the consumer starts reading:
        // everything beyond the window must have spilled
        awaitDrainDone(result);
        byte[] received = result.payload().attachments().get(0).contentStream().readAllBytes();

        assertArrayEquals(checksum(content), checksum(received));
        assertEquals(content.length, received.length);
        assertTrue(spooled.get() > 0, "test must exercise the overflow path");
        awaitState(result.exchange(), Exchange.State.COMPLETED);
        try (var files = Files.list(spoolDir)) {
            assertEquals(0, files.count(), "no residual spool files");
        }
    }

    @Test
    void upstreamIsFullyConsumedAndReleasedIndependentOfConsumerPace() throws Exception {
        byte[] content = new byte[256 * 1024];
        new Random(3).nextBytes(content);
        byte[] body = message(List.of("att-1"), List.of(content));
        CountingInputStream transport = new CountingInputStream(new ByteArrayInputStream(body));
        AtomicBoolean released = new AtomicBoolean();

        RestxopConfig config = RestxopConfig.builder()
                .memoryWindowPerPart(8 * 1024)
                .build();
        MessageReader r = newReader(config);
        ReadResult<TestPayload> result = r.read(CONTENT_TYPE, transport, payloadType(),
                () -> released.set(true));

        // Do not touch the attachment at all: the drain must still consume
        // the entire upstream and release it
        long deadline = System.currentTimeMillis() + 10_000;
        while ((!released.get() || transport.consumed < body.length)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(body.length, transport.consumed, "drain must consume the transport fully");
        assertTrue(released.get(), "upstream must be released at end-of-message");

        // The attachment is still fully readable from library buffers
        byte[] received = result.payload().attachments().get(0).contentStream().readAllBytes();
        assertArrayEquals(content, received);
        awaitState(result.exchange(), Exchange.State.COMPLETED);
    }

    @Test
    void zeroAttachmentMessageCompletesImmediately() throws Exception {
        byte[] body = message(List.of(), List.of());
        CountingInputStream transport = new CountingInputStream(new ByteArrayInputStream(body));
        AtomicBoolean released = new AtomicBoolean();

        MessageReader r = newReader(RestxopConfig.defaults());
        ReadResult<TestPayload> result = r.read(CONTENT_TYPE, transport, payloadType(),
                () -> released.set(true));

        assertEquals("{\"refs\":[]}", result.payload().json());
        assertEquals(Exchange.State.COMPLETED, result.exchange().state(),
                "zero-attachment exchange completes on the caller thread");
        assertEquals(body.length, transport.consumed);
        assertTrue(released.get());
    }

    @Test
    void callerRunsFallbackDrivesTheDrainWhenThePoolIsSaturated() throws Exception {
        RestxopConfig config = RestxopConfig.builder().drainPoolSize(1).build();
        MessageReader r = newReader(config);

        // Exchange A occupies the single drain worker: its transport blocks
        byte[] contentA = new byte[32 * 1024];
        byte[] bodyA = message(List.of("att-1"), List.of(contentA));
        GatedInputStream transportA = new GatedInputStream(bodyA, bodyA.length - 5_000);
        ReadResult<TestPayload> resultA = r.read(CONTENT_TYPE, transportA, payloadType(), null);

        // Wait until the pool worker is actually parked inside exchange A
        assertTrue(transportA.awaitBlocked(5, TimeUnit.SECONDS), "drain A must be in flight");

        // Exchange B cannot get a worker: the consumer's own read must drive it
        byte[] contentB = "caller-runs content".getBytes(StandardCharsets.UTF_8);
        ReadResult<TestPayload> resultB = r.read(CONTENT_TYPE,
                new ByteArrayInputStream(message(List.of("att-1"), List.of(contentB))),
                payloadType(), null);
        byte[] received = resultB.payload().attachments().get(0).contentStream().readAllBytes();
        assertArrayEquals(contentB, received);
        awaitState(resultB.exchange(), Exchange.State.COMPLETED);

        transportA.open();
        byte[] receivedA = resultA.payload().attachments().get(0).contentStream().readAllBytes();
        assertArrayEquals(contentA, receivedA);
        awaitState(resultA.exchange(), Exchange.State.COMPLETED);
    }

    @Test
    void attachmentsRemainReadableAfterDrainCompletion() throws Exception {
        byte[] content = "read me later".getBytes(StandardCharsets.UTF_8);
        MessageReader r = newReader(RestxopConfig.defaults());
        ReadResult<TestPayload> result = r.read(CONTENT_TYPE,
                new ByteArrayInputStream(message(List.of("att-1"), List.of(content))),
                payloadType(), null);

        awaitDrainDone(result);
        assertArrayEquals(content,
                result.payload().attachments().get(0).contentStream().readAllBytes());
    }

    private static void awaitDrainDone(ReadResult<?> result) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (result.exchange().drainState() != Exchange.DrainState.DONE
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(Exchange.DrainState.DONE, result.exchange().drainState());
    }

    private static void awaitState(Exchange exchange, Exchange.State expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (exchange.state() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, exchange.state());
    }

    private static byte[] checksum(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        assertNotEquals(-1, -1, "needle not found");
        return -1;
    }

    /** Serves bytes up to a gate position, then blocks until opened. */
    private static final class GatedInputStream extends InputStream {

        private final byte[] data;
        private final int gate;
        private int position;
        private final CountDownLatch openLatch = new CountDownLatch(1);
        private final CountDownLatch blockedLatch = new CountDownLatch(1);

        GatedInputStream(byte[] data, int gate) {
            this.data = data;
            this.gate = gate;
        }

        void open() {
            openLatch.countDown();
        }

        boolean awaitBlocked(long timeout, TimeUnit unit) throws InterruptedException {
            return blockedLatch.await(timeout, unit);
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
            if (position >= gate && openLatch.getCount() > 0) {
                blockedLatch.countDown();
                try {
                    if (!openLatch.await(30, TimeUnit.SECONDS)) {
                        throw new IOException("gate never opened");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted at gate", e);
                }
            }
            int bound = openLatch.getCount() > 0 ? gate : data.length;
            int n = Math.min(len, bound - position);
            if (n <= 0) {
                return read(b, off, len);
            }
            System.arraycopy(data, position, b, off, n);
            position += n;
            return n;
        }
    }

    private static final class CountingInputStream extends InputStream {

        private final InputStream delegate;
        volatile long consumed;

        CountingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                consumed++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                consumed += n;
            }
            return n;
        }
    }
}
