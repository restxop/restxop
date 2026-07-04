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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class FixturesTest {

    private static final String BOUNDARY = "rx-fixture-0001";

    @ParameterizedTest
    @ValueSource(strings = {
        "canonical/single-attachment.http",
        "canonical/multi-attachment.http",
        "canonical/nested-attachment.http",
        "canonical/null-attachment.http",
        "canonical/zero-attachment.http",
    })
    void canonicalFixturesAreWellFormed(String name) {
        WireFixture fixture = Fixtures.load(name);

        assertEquals(name, fixture.name());
        assertTrue(fixture.contentType().startsWith("multipart/related"), fixture.contentType());
        assertEquals(BOUNDARY, fixture.boundary());
        assertEquals("<root>", fixture.start());

        byte[] body = fixture.body();
        byte[] opening = ("\r\n--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
        assertArrayEquals(opening, Arrays.copyOfRange(body, 0, opening.length),
                "body must begin with the opening delimiter");
        byte[] closing = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
        assertArrayEquals(closing,
                Arrays.copyOfRange(body, body.length - closing.length, body.length),
                "body must end with the closing delimiter");

        String text = new String(body, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("Content-ID: <root>"), "root part must be present");
    }

    @Test
    void bodyOnlyLoadingUsesSuppliedContentType() {
        WireFixture withHeaders = Fixtures.load("canonical/zero-attachment.http");
        WireFixture bodyOnly = Fixtures.loadBodyOnly("canonical/zero-attachment.http",
                "composite/related; boundary=\"x\"");
        assertEquals("x", bodyOnly.boundary());
        assertTrue(bodyOnly.body().length > withHeaders.body().length,
                "body-only load must keep the header section as body bytes");
    }

    @Test
    void missingFixtureFailsClearly() {
        assertThrows(IllegalArgumentException.class, () -> Fixtures.load("canonical/nope.http"));
    }

    @Test
    void payloadModelsRoundTripTheirFields() {
        var report = new dev.restxop.testkit.model.ReportPayload("t", null);
        assertEquals("t", report.title);
        var bundle = new dev.restxop.testkit.model.BundlePayload("n", null, null);
        assertEquals("n", bundle.name);
        var nested = new dev.restxop.testkit.model.NestedPayload("l",
                new dev.restxop.testkit.model.NestedPayload.Inner(null));
        assertEquals("l", nested.label);
        var plain = new dev.restxop.testkit.model.PlainPayload("m", 7);
        assertEquals(List.of("m", 7), List.of(plain.message, plain.number));
    }
}
