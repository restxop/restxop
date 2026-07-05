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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.restxop.Attachment;
import dev.restxop.RestxopException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * SC-005: the two platform generations are wire-identical. Identical
 * payloads written with identical identifier settings must produce
 * byte-identical messages; each generation must read the other's output
 * checksum-exactly; and malformed inputs must surface the same typed error
 * through both stacks.
 */
@Timeout(120)
public abstract class CrossGenerationSuite {

    private static final String FIXTURE_FILENAME = "x.bin";

    /** Serializes through generation A (Boot 3 / Jackson 2 stack). */
    protected abstract EncodedMessage encodeGenerationA(Object payload, WriterSettings settings);

    /** Serializes through generation B (Boot 4 / Jackson 3 stack). */
    protected abstract EncodedMessage encodeGenerationB(Object payload, WriterSettings settings);

    /** Deserializes through generation A. */
    protected abstract <T> T decodeGenerationA(String contentType, InputStream body, Type type);

    /** Deserializes through generation B. */
    protected abstract <T> T decodeGenerationB(String contentType, InputStream body, Type type);

    private static byte[] content(int seed, int size) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private record Sample(String name, Supplier<Object> payload, Type type) {
    }

    /** One payload per canonical shape; suppliers so instances never cross stacks. */
    private List<Sample> samples() {
        return List.of(
                new Sample("single", () -> new ReportPayload("Quarterly report",
                        Attachment.builder(content(1, 64 * 1024))
                                .filename("data.bin")
                                .contentType("application/octet-stream")
                                .build()), ReportPayload.class),
                new Sample("multi", () -> new BundlePayload("bundle",
                        Attachment.builder(content(2, 8_000))
                                .filename("alpha.txt").contentType("text/plain").build(),
                        Attachment.builder(content(3, 9_000))
                                .filename("report.pdf").contentType("application/pdf").build()),
                        BundlePayload.class),
                new Sample("nested", () -> new NestedPayload("nested", new NestedPayload.Inner(
                        Attachment.builder(content(4, 5_000))
                                .contentType("application/octet-stream").build())),
                        NestedPayload.class),
                new Sample("null", () -> new ReportPayload("no report", null),
                        ReportPayload.class),
                new Sample("zero", () -> new PlainPayload("plain", 42), PlainPayload.class),
                new Sample("rich", CrossGenerationSuite::richPayload, RichPayload.class),
                new Sample("non-ascii-filename", () -> new ReportPayload("intl",
                        Attachment.builder(content(9, 2_000))
                                .filename("naïve – 文件.pdf")
                                .contentType("application/pdf")
                                .build()), ReportPayload.class));
    }

    private static RichPayload richPayload() {
        RichPayload payload = new RichPayload();
        payload.label = "rich";
        payload.inherited = Attachment.of(content(5, 10_000));
        payload.inner = new NestedPayload.Inner(Attachment.of(content(6, 11_000)));
        payload.items = List.of(Attachment.of(content(7, 12_000)));
        payload.byName = new LinkedHashMap<>(Map.of("only", Attachment.of(content(8, 13_000))));
        payload.missing = null;
        return payload;
    }

    @Test
    protected void identicalPayloadsProduceByteIdenticalMessages() {
        for (Sample sample : samples()) {
            EncodedMessage a = encodeGenerationA(sample.payload().get(), WriterSettings.fixture());
            EncodedMessage b = encodeGenerationB(sample.payload().get(), WriterSettings.fixture());
            assertEquals(a.contentType(), b.contentType(), sample.name());
            assertArrayEquals(a.body(), b.body(),
                    "wire bytes must be identical across generations for '" + sample.name() + "'");
        }
    }

    @Test
    protected void eachGenerationReadsTheOthersOutputChecksumExact() throws IOException {
        byte[] bytes = content(10, 700_000); // crosses the default memory window
        ReportPayload outgoing = new ReportPayload("cross",
                Attachment.builder(bytes).filename(FIXTURE_FILENAME).build());

        EncodedMessage fromA = encodeGenerationA(outgoing, WriterSettings.fixture());
        ReportPayload readByB = decodeGenerationB(fromA.contentType(),
                new ByteArrayInputStream(fromA.body()), ReportPayload.class);
        assertArrayEquals(bytes, readByB.report.contentStream().readAllBytes(), "A → B");
        assertEquals(FIXTURE_FILENAME, readByB.report.filename().orElseThrow());

        ReportPayload outgoing2 = new ReportPayload("cross",
                Attachment.builder(bytes).filename(FIXTURE_FILENAME).build());
        EncodedMessage fromB = encodeGenerationB(outgoing2, WriterSettings.fixture());
        ReportPayload readByA = decodeGenerationA(fromB.contentType(),
                new ByteArrayInputStream(fromB.body()), ReportPayload.class);
        assertArrayEquals(bytes, readByA.report.contentStream().readAllBytes(), "B → A");
        assertEquals(FIXTURE_FILENAME, readByA.report.filename().orElseThrow());
    }

    @Test
    protected void malformedInputsSurfaceTheSameTypedErrorsInBothGenerations() {
        List<String> fixtures = List.of(
                "malformed/missing-boundary.http",
                "malformed/missing-start.http",
                "malformed/missing-type-param.http",
                "malformed/wrong-first-part.http",
                "malformed/truncated-mid-headers.http");
        for (String name : fixtures) {
            WireFixture fixture = Fixtures.load(name);
            RestxopException errorA = assertThrows(RestxopException.class,
                    () -> readAllThroughA(fixture), name);
            RestxopException errorB = assertThrows(RestxopException.class,
                    () -> readAllThroughB(fixture), name);
            assertSame(errorA.getClass(), errorB.getClass(),
                    "error surface must be identical across generations for " + name);
        }
    }

    private void readAllThroughA(WireFixture fixture) throws IOException {
        ReportPayload payload = decodeGenerationA(fixture.contentType(),
                new ByteArrayInputStream(fixture.body()), ReportPayload.class);
        if (payload.report != null) {
            payload.report.contentStream().readAllBytes();
        }
    }

    private void readAllThroughB(WireFixture fixture) throws IOException {
        ReportPayload payload = decodeGenerationB(fixture.contentType(),
                new ByteArrayInputStream(fixture.body()), ReportPayload.class);
        if (payload.report != null) {
            payload.report.contentStream().readAllBytes();
        }
    }
}
