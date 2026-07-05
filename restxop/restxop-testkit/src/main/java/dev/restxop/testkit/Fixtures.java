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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Loads {@code .http} wire fixtures from the testkit's classpath. A fixture
 * file is either:
 *
 * <ul>
 *   <li>a header section (at minimum {@code Content-Type:}) terminated by a
 *       blank line, followed by the exact body bytes; or</li>
 *   <li>body-only (the captured legacy format), in which case the caller
 *       supplies the Content-Type.</li>
 * </ul>
 */
// Fixture-file scanning is one cohesive parser loop; the jumps mirror the
// record grammar
@SuppressWarnings({"java:S3776", "java:S135"})
public final class Fixtures {

    private static final String ROOT = "/fixtures/";

    private Fixtures() {
    }

    /** Loads a fixture with an embedded header section, e.g. {@code canonical/single-attachment.http}. */
    public static WireFixture load(String name) {
        byte[] raw = readAll(name);
        int bodyStart = -1;
        // Header section may use CRLF or LF; the blank line ends it
        for (int i = 0; i < raw.length - 1; i++) {
            if (raw[i] == '\n') {
                if (raw[i + 1] == '\n') {
                    bodyStart = i + 2;
                    break;
                }
                if (i + 2 < raw.length && raw[i + 1] == '\r' && raw[i + 2] == '\n') {
                    bodyStart = i + 3;
                    break;
                }
            }
        }
        if (bodyStart < 0) {
            throw new IllegalStateException("fixture '" + name + "' has no blank line after headers");
        }
        String headerSection = new String(raw, 0, bodyStart, StandardCharsets.ISO_8859_1);
        String contentType = null;
        for (String line : headerSection.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().toLowerCase(Locale.ROOT)
                    .equals("content-type")) {
                contentType = line.substring(colon + 1).trim();
            }
        }
        if (contentType == null) {
            throw new IllegalStateException("fixture '" + name + "' declares no Content-Type header");
        }
        return new WireFixture(name, contentType, Arrays.copyOfRange(raw, bodyStart, raw.length));
    }

    /** Loads a body-only fixture (captured legacy format) with a caller-supplied Content-Type. */
    public static WireFixture loadBodyOnly(String name, String contentType) {
        return new WireFixture(name, contentType, readAll(name));
    }

    private static byte[] readAll(String name) {
        String resource = ROOT + name;
        try (InputStream in = Fixtures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no such fixture resource: " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read fixture " + resource, e);
        }
    }
}
