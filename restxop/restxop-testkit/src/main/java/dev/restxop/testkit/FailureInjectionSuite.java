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
import static org.junit.jupiter.api.Assertions.fail;

import dev.restxop.Attachment;
import dev.restxop.ExchangeTimeoutException;
import dev.restxop.LimitExceededException;
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
import dev.restxop.testkit.model.ReportPayload;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * SC-004 failure-injection suite (tag {@code failure}): the source severed
 * at every message phase, malformed inputs, consumer abandonment, early
 * close, deadline expiry, and write-path failures. Every case asserts a
 * descriptive typed error (never an NPE or a hang), zero residual spool
 * files, and a released upstream connection.
 */
@Tag("failure")
@Timeout(60)
// Wire strings keep explicit \r\n (text blocks would obscure the CRLF
// bytes under test); consumer threads capture Throwable so cross-thread
// failures reach the assertion
@SuppressWarnings({"java:S6126", "java:S1181"})
public abstract class FailureInjectionSuite {

    private static final String EXCHANGE_FAILED_EVENT = "exchangeFailed";
    private static final String ATT1_CONTENT_ID = "Content-ID: <att-1>";

    protected static final String CONTENT_TYPE = "multipart/related; type=\"application/json\"; "
            + "boundary=\"rx-fixture-0001\"; start=\"<root>\"";

    @TempDir
    protected Path spoolDir;

    private final List<MessageReader> openReaders = new ArrayList<>();

    /** The codec under test; the default stub isolates protocol behavior. */
    protected RootPartCodec codec() {
        return new StubRootPartCodec();
    }

    @AfterEach
    void closeReaders() {
        openReaders.forEach(MessageReader::close);
        openReaders.clear();
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    protected RestxopConfig.Builder config() {
        return RestxopConfig.builder()
                .spoolDirectory(spoolDir)
                .memoryWindowPerPart(4 * 1024)
                .readWait(Duration.ofMillis(500))
                .exchangeTtl(Duration.ofSeconds(5));
    }

    private MessageReader reader(RestxopConfig config, ListenerCapture capture) {
        MessageReader reader = new MessageReader(config, codec(), new FileSpoolStorage(),
                List.of(capture));
        openReaders.add(reader);
        return reader;
    }

    private static MessageWriter.WriterIds fixtureIds() {
        AtomicInteger next = new AtomicInteger();
        return new MessageWriter.WriterIds("rx-fixture-0001", "root",
                () -> "att-" + next.incrementAndGet());
    }

    /** Serializes one single-attachment sample message to bytes. */
    protected byte[] encodeSample(byte[] content) throws IOException {
        ReportPayload payload = new ReportPayload("sample",
                Attachment.builder(content).filename("f.bin").build());
        MessageWriter writer = new MessageWriter(config().build(), codec(), fixtureIds());
        Exchange exchange = Exchange.open(config().build(), List.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(exchange, payload, out);
        exchange.complete();
        return out.toByteArray();
    }

    /** Serializes one two-attachment sample message to bytes. */
    protected byte[] encodeBundle(byte[] first, byte[] second) throws IOException {
        dev.restxop.testkit.model.BundlePayload payload =
                new dev.restxop.testkit.model.BundlePayload("bundle",
                        Attachment.builder(first).filename("a.bin").build(),
                        Attachment.builder(second).filename("b.bin").build());
        MessageWriter writer = new MessageWriter(config().build(), codec(), fixtureIds());
        Exchange exchange = Exchange.open(config().build(), List.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(exchange, payload, out);
        exchange.complete();
        return out.toByteArray();
    }

    private static byte[] sampleContent(int size) {
        byte[] content = new byte[size];
        new Random(4711).nextBytes(content);
        return content;
    }

    protected static int indexOf(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i <= haystack.length - n.length; i++) {
            if (matchesAt(haystack, i, n)) {
                return i;
            }
        }
        throw new IllegalStateException("marker not found: " + needle);
    }

    private static byte[] cut(byte[] data, int at) {
        byte[] result = new byte[at];
        System.arraycopy(data, 0, result, 0, at);
        return result;
    }

    private void assertHygiene(SpoolHygiene.ConnectionProbe probe, ListenerCapture capture)
            throws InterruptedException {
        assertTrue(capture.awaitClosed(10, TimeUnit.SECONDS), "exchange must terminate");
        probe.assertReleased();
        SpoolHygiene.assertNoResidualSpoolFiles(spoolDir);
    }

    /** Read that is expected to fail synchronously at the root phase. */
    private RestxopException assertRootPhaseFailure(String contentType, byte[] body,
            RestxopConfig config) throws InterruptedException {
        ListenerCapture capture = new ListenerCapture();
        SpoolHygiene.ConnectionProbe probe = new SpoolHygiene.ConnectionProbe();
        MessageReader reader = reader(config, capture);
        RestxopException error = assertThrows(RestxopException.class,
                () -> reader.read(contentType, new ByteArrayInputStream(body),
                        ResolvableTypeInfo.of(ReportPayload.class), probe.asReleaseHandle()));
        assertHygiene(probe, capture);
        assertTrue(capture.has(EXCHANGE_FAILED_EVENT), capture.names().toString());
        return error;
    }

    /** Read that succeeds at the root but whose drain is expected to fail. */
    private RestxopException assertDrainPhaseFailure(byte[] body, RestxopConfig config)
            throws Exception {
        ListenerCapture capture = new ListenerCapture();
        SpoolHygiene.ConnectionProbe probe = new SpoolHygiene.ConnectionProbe();
        MessageReader reader = reader(config, capture);
        ReadResult<ReportPayload> result = reader.read(CONTENT_TYPE,
                new ByteArrayInputStream(body), ResolvableTypeInfo.of(ReportPayload.class),
                probe.asReleaseHandle());
        assertNotNull(result.payload().title, "payload delivered before the failure");
        RestxopException error = assertThrows(RestxopException.class,
                () -> result.payload().report.contentStream().readAllBytes());
        assertHygiene(probe, capture);
        assertTrue(capture.has("payloadDelivered"), capture.names().toString());
        assertTrue(capture.has(EXCHANGE_FAILED_EVENT), capture.names().toString());
        return error;
    }

    // ------------------------------------------------------------------
    // Source severed at every phase
    // ------------------------------------------------------------------

    @Test
    protected void severedBeforeTheFirstDelimiter() throws Exception {
        assertInstanceOf(MalformedMessageException.class,
                assertRootPhaseFailure(CONTENT_TYPE, new byte[0], config().build()));
    }

    @Test
    protected void severedInsideTheRootContent() throws Exception {
        byte[] full = encodeSample(sampleContent(64));
        byte[] severed = cut(full, indexOf(full, "\"title\":\"sample\"") + 5);
        assertInstanceOf(MalformedMessageException.class,
                assertRootPhaseFailure(CONTENT_TYPE, severed, config().build()));
    }

    @Test
    protected void severedInsideThePartHeaders() throws Exception {
        byte[] full = encodeSample(sampleContent(64));
        byte[] severed = cut(full, indexOf(full, ATT1_CONTENT_ID) + 10);
        assertInstanceOf(MalformedMessageException.class,
                assertDrainPhaseFailure(severed, config().build()));
    }

    @Test
    protected void severedMidContentUnblocksAnAlreadyBlockedReader() throws Exception {
        byte[] full = encodeSample(sampleContent(64 * 1024));
        int gateAt = indexOf(full, ATT1_CONTENT_ID) + 2048;
        SeverableInputStream transport = new SeverableInputStream(full, gateAt);
        ListenerCapture capture = new ListenerCapture();
        SpoolHygiene.ConnectionProbe probe = new SpoolHygiene.ConnectionProbe();
        MessageReader reader = reader(config().build(), capture);

        ReadResult<ReportPayload> result = reader.read(CONTENT_TYPE, transport,
                ResolvableTypeInfo.of(ReportPayload.class), probe.asReleaseHandle());
        InputStream attachment = result.payload().report.contentStream();
        attachment.readNBytes(1024); // partial read, then block on the gate

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            try {
                attachment.readAllBytes();
                fail("read must not succeed past the severed source");
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        consumer.start();
        assertTrue(transport.awaitBlocked(5, TimeUnit.SECONDS), "drain must reach the gate");
        Thread.sleep(100); // let the consumer park on the buffer condition

        long severedAt = System.nanoTime();
        transport.sever(new IOException("connection reset by injection"));
        assertTrue(done.await(5, TimeUnit.SECONDS), "blocked reader must be unblocked");
        long wokenMillis = (System.nanoTime() - severedAt) / 1_000_000;

        assertTrue(wokenMillis < 550, "already-blocked reader must wake promptly within the "
                + "timeout+10% margin (FR-021/SC-004), took " + wokenMillis + " ms");
        RestxopException error = assertInstanceOf(RestxopException.class, thrown.get());
        assertTrue(String.valueOf(error).contains("exchange"), error.toString());
        assertHygiene(probe, capture);
        assertTrue(capture.has(EXCHANGE_FAILED_EVENT));
    }

    @Test
    protected void severedDuringTheEpilogueEndsTerminallyWithoutLeaks() throws Exception {
        byte[] full = encodeSample(sampleContent(256));
        byte[] withEpilogue = new byte[full.length + 64];
        System.arraycopy(full, 0, withEpilogue, 0, full.length);
        SeverableInputStream transport = new SeverableInputStream(withEpilogue, full.length + 16);
        ListenerCapture capture = new ListenerCapture();
        SpoolHygiene.ConnectionProbe probe = new SpoolHygiene.ConnectionProbe();
        MessageReader reader = reader(config().build(), capture);

        ReadResult<ReportPayload> result = reader.read(CONTENT_TYPE, transport,
                ResolvableTypeInfo.of(ReportPayload.class), probe.asReleaseHandle());
        assertTrue(transport.awaitBlocked(5, TimeUnit.SECONDS));
        transport.sever(new IOException("severed in epilogue"));

        // All parts arrived; the failure may only surface as a failed
        // exchange, never as a hang or leak
        assertHygiene(probe, capture);
        Exchange.State state = result.exchange().state();
        assertTrue(state == Exchange.State.FAILED || state == Exchange.State.COMPLETED,
                "terminal state required, was " + state);
    }

    // ------------------------------------------------------------------
    // Malformed inputs (fixtures are the authority)
    // ------------------------------------------------------------------

    @Test
    protected void missingOuterContentTypeIsMalformed() throws Exception {
        assertInstanceOf(MalformedMessageException.class,
                assertRootPhaseFailure(null, encodeSample(sampleContent(16)), config().build()));
    }

    @Test
    protected void missingBoundaryParameterIsMalformed() throws Exception {
        WireFixture fixture = Fixtures.load("malformed/missing-boundary.http");
        assertInstanceOf(MalformedMessageException.class,
                assertRootPhaseFailure(fixture.contentType(), fixture.body(), config().build()));
    }

    @Test
    protected void missingStartParameterIsMalformed() throws Exception {
        WireFixture fixture = Fixtures.load("malformed/missing-start.http");
        assertInstanceOf(MalformedMessageException.class,
                assertRootPhaseFailure(fixture.contentType(), fixture.body(), config().build()));
    }

    @Test
    protected void missingTypeParameterIsMalformed() throws Exception {
        WireFixture fixture = Fixtures.load("malformed/missing-type-param.http");
        assertInstanceOf(MalformedMessageException.class,
                assertRootPhaseFailure(fixture.contentType(), fixture.body(), config().build()));
    }

    @Test
    protected void firstPartNotMatchingStartIsMalformed() throws Exception {
        WireFixture fixture = Fixtures.load("malformed/wrong-first-part.http");
        RestxopException error = assertRootPhaseFailure(fixture.contentType(), fixture.body(),
                config().build());
        assertInstanceOf(MalformedMessageException.class, error);
        assertTrue(error.getMessage().contains("root") || error.getMessage().contains("start"),
                error.getMessage());
    }

    @Test
    protected void missingPartContentIdIsMalformed() throws Exception {
        WireFixture fixture = Fixtures.load("malformed/missing-part-content-id.http");
        assertInstanceOf(MalformedMessageException.class,
                assertDrainPhaseFailure(fixture.body(), config().build()));
    }

    @Test
    protected void truncationMidContentIsMalformed() throws Exception {
        WireFixture fixture = Fixtures.load("malformed/truncated-mid-content.http");
        assertInstanceOf(MalformedMessageException.class,
                assertDrainPhaseFailure(fixture.body(), config().build()));
    }

    @Test
    protected void truncationMidHeadersIsMalformed() throws Exception {
        WireFixture fixture = Fixtures.load("malformed/truncated-mid-headers.http");
        assertInstanceOf(MalformedMessageException.class,
                assertDrainPhaseFailure(fixture.body(), config().build()));
    }

    @Test
    protected void oversizedPartHeaderHitsItsBound() throws Exception {
        WireFixture fixture = Fixtures.load("malformed/oversized-header.http");
        RestxopException error = assertDrainPhaseFailure(fixture.body(), config().build());
        LimitExceededException limit = assertInstanceOf(LimitExceededException.class, error);
        assertEquals("limits.max-part-header-bytes", limit.limitName());
    }

    @Test
    protected void oversizedRootPartHitsItsBound() throws Exception {
        WireFixture fixture = Fixtures.load("malformed/oversized-root.http");
        RestxopException error = assertRootPhaseFailure(fixture.contentType(), fixture.body(),
                config().maxRootPartBytes(64 * 1024).build());
        LimitExceededException limit = assertInstanceOf(LimitExceededException.class, error);
        assertEquals("limits.max-root-part-bytes", limit.limitName());
        assertEquals(64 * 1024, limit.configuredValue());
    }

    @Test
    protected void partCountBreachHitsItsBound() throws Exception {
        WireFixture fixture = Fixtures.load("malformed/too-many-parts.http");
        ListenerCapture capture = new ListenerCapture();
        SpoolHygiene.ConnectionProbe probe = new SpoolHygiene.ConnectionProbe();
        MessageReader reader = reader(config().maxParts(3).build(), capture);
        // Root parses (no references), so the caller-thread drain fails
        RestxopException error = assertThrows(RestxopException.class,
                () -> reader.read(fixture.contentType(), new ByteArrayInputStream(fixture.body()),
                        ResolvableTypeInfo.of(ReportPayload.class), probe.asReleaseHandle()));
        LimitExceededException limit = assertInstanceOf(LimitExceededException.class, error);
        assertEquals("limits.max-parts", limit.limitName());
        assertEquals(3, limit.configuredValue());
        assertHygiene(probe, capture);
    }

    // ------------------------------------------------------------------
    // Abandonment, early close, deadlines
    // ------------------------------------------------------------------

    @Test
    protected void abandonedExchangeIsReclaimedByTheTtlReaper() throws Exception {
        byte[] body = encodeSample(sampleContent(64 * 1024)); // spills past the 4 KiB window
        ListenerCapture capture = new ListenerCapture();
        SpoolHygiene.ConnectionProbe probe = new SpoolHygiene.ConnectionProbe();
        RestxopConfig config = config()
                .exchangeTtl(Duration.ofMillis(800))
                .readWait(Duration.ofMillis(400))
                .build();
        MessageReader reader = reader(config, capture);

        ReadResult<ReportPayload> result = reader.read(CONTENT_TYPE,
                new ByteArrayInputStream(body), ResolvableTypeInfo.of(ReportPayload.class),
                probe.asReleaseHandle());
        // Abandon: never touch the attachment
        assertTrue(capture.awaitClosed(10, TimeUnit.SECONDS), "reaper must reclaim");
        assertEquals(Exchange.State.RECLAIMED, result.exchange().state());
        assertInstanceOf(ExchangeTimeoutException.class, capture.failureCause().orElseThrow());
        assertTrue(capture.maxSpooled() > 0, "test must exercise the spool path");
        probe.assertReleased();
        SpoolHygiene.assertNoResidualSpoolFiles(spoolDir);
        assertThrows(RestxopException.class,
                () -> result.payload().report.contentStream().read(new byte[16], 0, 16),
                "reads after reclamation must fail typed");
    }

    @Test
    protected void earlyCloseDrainsAndDiscardsWithoutSpoolCapAccrual() throws Exception {
        byte[] content = sampleContent(200 * 1024);
        byte[] body = encodeSample(content);
        // Cap far below the content size: buffering the remainder would breach it
        RestxopConfig config = config().spoolMaxPerAttachment(8 * 1024)
                .spoolMaxPerMessage(16 * 1024).build();
        int gateAt = indexOf(body, ATT1_CONTENT_ID) + 2048;
        GatedInputStream transport = new GatedInputStream(body, gateAt);
        ListenerCapture capture = new ListenerCapture();
        SpoolHygiene.ConnectionProbe probe = new SpoolHygiene.ConnectionProbe();
        MessageReader reader = reader(config, capture);

        ReadResult<ReportPayload> result = reader.read(CONTENT_TYPE, transport,
                ResolvableTypeInfo.of(ReportPayload.class), probe.asReleaseHandle());
        InputStream attachment = result.payload().report.contentStream();
        byte[] head = attachment.readNBytes(512);
        assertArrayEquals(java.util.Arrays.copyOf(content, 512), head, "prefix byte-exact");
        attachment.close(); // early close: rest must be drained and dropped
        transport.open();

        assertHygiene(probe, capture);
        assertEquals(Exchange.State.COMPLETED, result.exchange().state(),
                "discarded remainder must not fail the exchange");
        assertTrue(capture.maxSpooled() <= 8 * 1024,
                "discarded bytes must not accrue against spool caps, spooled "
                        + capture.maxSpooled());
        assertTrue(capture.has("attachmentConsumed"));
    }

    @Test
    protected void readWaitDeadlineExpiresAgainstAStalledSource() throws Exception {
        byte[] body = encodeSample(sampleContent(64 * 1024));
        int gateAt = indexOf(body, ATT1_CONTENT_ID) + 4096 + 2048;
        SeverableInputStream transport = new SeverableInputStream(body, gateAt);
        ListenerCapture capture = new ListenerCapture();
        SpoolHygiene.ConnectionProbe probe = new SpoolHygiene.ConnectionProbe();
        RestxopConfig config = config().readWait(Duration.ofMillis(400))
                .exchangeTtl(Duration.ofSeconds(5)).build();
        MessageReader reader = reader(config, capture);

        ReadResult<ReportPayload> result = reader.read(CONTENT_TYPE, transport,
                ResolvableTypeInfo.of(ReportPayload.class), probe.asReleaseHandle());
        InputStream attachment = result.payload().report.contentStream();
        attachment.readNBytes(4096 + 1024); // catch up with the drain, then stall

        long start = System.nanoTime();
        assertThrows(ExchangeTimeoutException.class, attachment::readAllBytes);
        long waitedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(waitedMillis >= 380, "deadline must be honored, waited " + waitedMillis);
        assertTrue(waitedMillis <= 440 + 1000,
                "timeout+10%+margin exceeded (SC-004): " + waitedMillis + " ms");

        transport.sever(new IOException("test cleanup"));
        assertHygiene(probe, capture);
    }

    @Test
    protected void ttlWakesAReaderBlockedLongerThanItsReadWait() throws Exception {
        byte[] body = encodeSample(sampleContent(64 * 1024));
        int gateAt = indexOf(body, ATT1_CONTENT_ID) + 1024;
        // The source trickles a byte every 150 ms: the reader keeps making
        // progress inside its read-wait, so only the TTL can end the wait —
        // deterministically exercising reclaim-wakes-blocked-reader
        TricklingInputStream transport = new TricklingInputStream(body, gateAt, 150);
        ListenerCapture capture = new ListenerCapture();
        SpoolHygiene.ConnectionProbe probe = new SpoolHygiene.ConnectionProbe();
        RestxopConfig config = config().exchangeTtl(Duration.ofMillis(700))
                .readWait(Duration.ofMillis(700)).build();
        MessageReader reader = reader(config, capture);

        ReadResult<ReportPayload> result = reader.read(CONTENT_TYPE, transport,
                ResolvableTypeInfo.of(ReportPayload.class), probe.asReleaseHandle());
        RestxopException error = assertThrows(RestxopException.class,
                () -> result.payload().report.contentStream().readAllBytes());
        assertInstanceOf(ExchangeTimeoutException.class, error,
                "TTL reclamation must wake the blocked reader with the timeout cause");

        transport.sever(new IOException("test cleanup"));
        assertHygiene(probe, capture);
        assertEquals(Exchange.State.RECLAIMED, result.exchange().state());
    }

    @Test
    protected void spoolCapBreachDuringDrainFailsTheExchangeTyped() throws Exception {
        byte[] body = encodeSample(sampleContent(64 * 1024));
        ListenerCapture capture = new ListenerCapture();
        SpoolHygiene.ConnectionProbe probe = new SpoolHygiene.ConnectionProbe();
        // Drain outruns the (absent) consumer; the overflow hits the cap
        RestxopConfig config = config().memoryWindowPerPart(4 * 1024)
                .spoolMaxPerAttachment(8 * 1024).spoolMaxPerMessage(16 * 1024).build();
        MessageReader reader = reader(config, capture);

        ReadResult<ReportPayload> result = reader.read(CONTENT_TYPE,
                new ByteArrayInputStream(body), ResolvableTypeInfo.of(ReportPayload.class),
                probe.asReleaseHandle());
        RestxopException error = assertThrows(RestxopException.class,
                () -> result.payload().report.contentStream().readAllBytes());
        LimitExceededException limit = assertInstanceOf(LimitExceededException.class, error);
        assertEquals("spool.max-per-attachment", limit.limitName());
        assertEquals(8 * 1024, limit.configuredValue());
        assertHygiene(probe, capture);
        assertInstanceOf(LimitExceededException.class, capture.failureCause().orElseThrow(),
                "spool-cap breach must surface through the listener (FR-033)");
    }

    @Test
    protected void unreferencedPartIsSkippedLenientlyAndTheExchangeSucceeds() throws Exception {
        byte[] content = sampleContent(1024);
        byte[] body = encodeSample(content);
        // Splice an unreferenced part in front of the closing delimiter
        String extra = "\r\n--rx-fixture-0001\r\n"
                + "Content-ID: <never-referenced>\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Transfer-Encoding: binary\r\n\r\n"
                + "orphan bytes the payload never mentions";
        int closingAt = indexOf(body, "\r\n--rx-fixture-0001--");
        ByteArrayOutputStream spliced = new ByteArrayOutputStream();
        spliced.write(body, 0, closingAt);
        spliced.write(extra.getBytes(StandardCharsets.ISO_8859_1));
        spliced.write(body, closingAt, body.length - closingAt);
        ListenerCapture capture = new ListenerCapture();
        SpoolHygiene.ConnectionProbe probe = new SpoolHygiene.ConnectionProbe();
        MessageReader reader = reader(config().build(), capture);

        ReadResult<ReportPayload> result = reader.read(CONTENT_TYPE,
                new ByteArrayInputStream(spliced.toByteArray()),
                ResolvableTypeInfo.of(ReportPayload.class), probe.asReleaseHandle());
        assertArrayEquals(content, result.payload().report.contentStream().readAllBytes());

        assertHygiene(probe, capture);
        assertEquals(Exchange.State.COMPLETED, result.exchange().state(),
                "unreferenced parts are skipped with a warning, not an error");
        assertTrue(capture.has("attachmentConsumed"));
        assertEquals(java.util.Optional.empty(), capture.failureCause());
    }

    @Test
    protected void discardingAnAttachmentFreesItsAggregateSpoolShare() throws Exception {
        byte[] first = sampleContent(24 * 1024);
        byte[] second = sampleContent(24 * 1024);
        byte[] body = encodeBundle(first, second);
        // Per-message cap fits either part's overflow, but not both: the
        // exchange only survives if discarding the first frees its share
        RestxopConfig config = config().memoryWindowPerPart(4 * 1024)
                .spoolMaxPerAttachment(32 * 1024).spoolMaxPerMessage(32 * 1024).build();
        int gateAt = indexOf(body, "Content-ID: <att-2>") + 512;
        GatedInputStream transport = new GatedInputStream(body, gateAt);
        ListenerCapture capture = new ListenerCapture();
        SpoolHygiene.ConnectionProbe probe = new SpoolHygiene.ConnectionProbe();
        MessageReader reader = reader(config, capture);

        ReadResult<dev.restxop.testkit.model.BundlePayload> result = reader.read(CONTENT_TYPE,
                transport, ResolvableTypeInfo.of(dev.restxop.testkit.model.BundlePayload.class),
                probe.asReleaseHandle());
        // The drain has fully spooled the first part behind the gate; close
        // it unread, freeing its aggregate share before the second arrives
        result.payload().first.contentStream().close();
        transport.open();

        byte[] receivedSecond = result.payload().second.contentStream().readAllBytes();
        assertArrayEquals(second, receivedSecond);
        assertHygiene(probe, capture);
        assertEquals(Exchange.State.COMPLETED, result.exchange().state(),
                "discarded spool share must not count against the per-message cap (T042)");
    }

    // ------------------------------------------------------------------
    // Write-path injection (FR-014)
    // ------------------------------------------------------------------

    private Exchange writeExpectingFailure(Object payload, OutputStream out,
            ListenerCapture capture) {
        RestxopConfig config = config().build();
        Exchange exchange = Exchange.open(config, List.of(capture));
        MessageWriter writer = new MessageWriter(config, codec(), fixtureIds());
        try {
            writer.write(exchange, payload, out);
            exchange.complete();
            fail("write must fail");
        } catch (IOException | RuntimeException e) {
            exchange.fail(e);
        }
        return exchange;
    }

    @Test
    protected void inaccessibleAttachmentSourceAbortsTheWrite() throws Exception {
        ReportPayload payload = new ReportPayload("bad-source",
                Attachment.of(spoolDir.resolve("does-not-exist.bin")));
        ListenerCapture capture = new ListenerCapture();

        Exchange exchange = writeExpectingFailure(payload, new ByteArrayOutputStream(), capture);

        assertEquals(Exchange.State.FAILED, exchange.state());
        Throwable cause = exchange.failureCause().orElseThrow();
        assertTrue(String.valueOf(cause.getMessage()).contains("does-not-exist")
                        || cause instanceof IOException,
                "descriptive typed error required, was " + cause);
        assertTrue(capture.has(EXCHANGE_FAILED_EVENT));
        assertTrue(capture.has("exchangeClosed"));
        SpoolHygiene.assertNoResidualSpoolFiles(spoolDir);
    }

    @Test
    protected void serializationFailureMidRootAbortsTheWrite() throws Exception {
        ListenerCapture capture = new ListenerCapture();

        Exchange exchange = writeExpectingFailure(new StubRootPartCodec.FailingPayload(),
                new ByteArrayOutputStream(), capture);

        assertEquals(Exchange.State.FAILED, exchange.state());
        assertTrue(exchange.failureCause().orElseThrow().getMessage().contains("serialization"),
                String.valueOf(exchange.failureCause().orElseThrow()));
        assertTrue(capture.has(EXCHANGE_FAILED_EVENT));
    }

    @Test
    protected void outputFailureMidPartAbortsAndReleasesTheSource() throws Exception {
        AtomicReference<Boolean> sourceClosed = new AtomicReference<>(false);
        InputStream source = new ByteArrayInputStream(sampleContent(64 * 1024)) {
            @Override
            public void close() {
                sourceClosed.set(true);
            }
        };
        ReportPayload payload = new ReportPayload("output-failure", Attachment.of(source));
        OutputStream failing = new OutputStream() {
            private int written;

            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                written += len;
                if (written > 8 * 1024) {
                    throw new IOException("injected output failure mid-part");
                }
            }
        };
        ListenerCapture capture = new ListenerCapture();

        Exchange exchange = writeExpectingFailure(payload, failing, capture);

        assertEquals(Exchange.State.FAILED, exchange.state());
        assertTrue(exchange.failureCause().orElseThrow().getMessage().contains("injected"),
                String.valueOf(exchange.failureCause().orElseThrow()));
        assertTrue(sourceClosed.get(), "attachment source must be closed on abort (FR-014)");
        assertTrue(capture.has(EXCHANGE_FAILED_EVENT));
        SpoolHygiene.assertNoResidualSpoolFiles(spoolDir);
    }

    // ------------------------------------------------------------------
    // Controllable transports
    // ------------------------------------------------------------------

    /** Serves bytes up to a gate, then blocks until opened (then serves the rest). */
    protected static final class GatedInputStream extends InputStream {

        private final byte[] data;
        private final int gate;
        private int position;
        private final CountDownLatch openLatch = new CountDownLatch(1);

        GatedInputStream(byte[] data, int gate) {
            this.data = data;
            this.gate = gate;
        }

        void open() {
            openLatch.countDown();
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

    /** Serves bytes to a gate, then trickles one byte per interval until severed. */
    protected static final class TricklingInputStream extends InputStream {

        private final byte[] data;
        private final int gate;
        private final long intervalMillis;
        private int position;
        private final AtomicReference<IOException> failure = new AtomicReference<>();

        TricklingInputStream(byte[] data, int gate, long intervalMillis) {
            this.data = data;
            this.gate = Math.min(gate, data.length);
            this.intervalMillis = intervalMillis;
        }

        void sever(IOException cause) {
            failure.set(cause);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            IOException severedBy = failure.get();
            if (severedBy != null) {
                throw severedBy;
            }
            if (position < gate) {
                int n = Math.min(len, gate - position);
                System.arraycopy(data, position, b, off, n);
                position += n;
                return n;
            }
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while trickling", e);
            }
            IOException severedWhileParked = failure.get();
            if (severedWhileParked != null) {
                throw severedWhileParked;
            }
            if (position >= data.length) {
                return -1;
            }
            b[off] = data[position++];
            return 1;
        }
    }

    /** Serves bytes up to a gate, blocks, and can be severed with an IOException. */
    protected static final class SeverableInputStream extends InputStream {

        private final byte[] data;
        private final int gate;
        private int position;
        private final CountDownLatch severed = new CountDownLatch(1);
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final AtomicReference<IOException> failure = new AtomicReference<>();

        SeverableInputStream(byte[] data, int gate) {
            this.data = data;
            this.gate = Math.min(gate, data.length);
        }

        void sever(IOException cause) {
            failure.set(cause);
            severed.countDown();
        }

        boolean awaitBlocked(long timeout, TimeUnit unit) throws InterruptedException {
            return blocked.await(timeout, unit);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            IOException severedBy = failure.get();
            if (severedBy != null) {
                throw severedBy;
            }
            if (position >= gate) {
                blocked.countDown();
                try {
                    if (!severed.await(30, TimeUnit.SECONDS)) {
                        throw new IOException("sever never signalled");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted awaiting sever", e);
                }
                throw failure.get();
            }
            int n = Math.min(len, gate - position);
            System.arraycopy(data, position, b, off, n);
            position += n;
            return n;
        }
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
