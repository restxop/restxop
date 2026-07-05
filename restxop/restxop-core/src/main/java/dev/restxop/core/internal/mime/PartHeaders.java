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
package dev.restxop.core.internal.mime;

import dev.restxop.LimitExceededException;
import dev.restxop.MalformedMessageException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One part's RFC-822-style header block, read from the part stream through
 * the terminating blank line and no further. Names are case-insensitive,
 * values trimmed, folded continuation lines unfolded on read, CRLF and bare
 * LF endings accepted, bytes interpreted as ISO-8859-1. The total block size
 * is bounded by {@code limits.max-part-header-bytes}.
 */
public final class PartHeaders {

    private final Map<String, List<String>> values;

    private PartHeaders(Map<String, List<String>> values) {
        this.values = values;
    }

    public static PartHeaders parse(InputStream in, int maxHeaderBytes, String exchangeId)
            throws IOException {
        List<String> logicalLines = readLogicalLines(in, maxHeaderBytes, exchangeId);
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (String line : logicalLines) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new MalformedMessageException(exchangeId,
                        "part header line has no name-colon-value shape: '" + abbreviate(line) + "'");
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return new PartHeaders(values);
    }

    // Header-block scanning (folding, CRLF/LF, bounds) is one cohesive
    // automaton pinned by the parsing test suite
    @SuppressWarnings("java:S3776")
    private static List<String> readLogicalLines(InputStream in, int maxHeaderBytes,
            String exchangeId) throws IOException {
        List<String> lines = new ArrayList<>();
        ByteArrayOutputStream current = new ByteArrayOutputStream();
        int total = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new MalformedMessageException(exchangeId,
                        "part header block truncated: end of part before the blank line");
            }
            if (++total > maxHeaderBytes) {
                throw new LimitExceededException(exchangeId, "limits.max-part-header-bytes",
                        maxHeaderBytes, "part header block exceeds the configured bound");
            }
            if (b == '\n') {
                String line = current.toString(StandardCharsets.ISO_8859_1);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                current.reset();
                if (line.isEmpty()) {
                    return lines; // blank line terminates the block
                }
                if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && !lines.isEmpty()) {
                    // Folded continuation: unfold by dropping the line break
                    lines.set(lines.size() - 1, lines.get(lines.size() - 1) + line);
                } else if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                    throw new MalformedMessageException(exchangeId,
                            "part header block starts with a continuation line");
                } else {
                    lines.add(line);
                }
            } else {
                current.write(b);
            }
        }
    }

    private static String abbreviate(String line) {
        return line.length() <= 60 ? line : line.substring(0, 57) + "...";
    }

    /** First value of the named header, matched case-insensitively. */
    public Optional<String> firstValue(String name) {
        List<String> list = values.get(name.toLowerCase(Locale.ROOT));
        return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** The part's Content-ID, normalized per wire-format §4. */
    public Optional<String> contentId() {
        return firstValue("content-id").map(IdNormalizer::normalize);
    }

    /** Raw Content-Type header value. */
    public Optional<String> contentType() {
        return firstValue("content-type");
    }

    /**
     * Filename from Content-Disposition: RFC 6266 {@code filename*}
     * (percent-decoded) preferred, then {@code filename}, then the legacy
     * library's {@code name} parameter.
     */
    public Optional<String> filename() {
        Optional<String> disposition = firstValue("content-disposition");
        if (disposition.isEmpty()) {
            return Optional.empty();
        }
        ContentTypeParams params = ContentTypeParams.parse(disposition.get());
        Optional<String> extended = params.parameter("filename*").map(PartHeaders::decodeExtValue);
        if (extended.isPresent()) {
            return extended;
        }
        Optional<String> plain = params.parameter("filename");
        if (plain.isPresent()) {
            return plain;
        }
        return params.parameter("name");
    }

    /** Decodes an RFC 5987 ext-value: {@code charset'language'percent-encoded}. */
    // Percent-decoding consumes two extra chars per escape; advancing the
    // index inside the loop is the canonical decoder idiom
    @SuppressWarnings("java:S127")
    private static String decodeExtValue(String extValue) {
        int firstQuote = extValue.indexOf('\'');
        int secondQuote = firstQuote < 0 ? -1 : extValue.indexOf('\'', firstQuote + 1);
        if (secondQuote < 0) {
            return extValue; // not ext-value shaped: expose as-is
        }
        String charsetName = extValue.substring(0, firstQuote);
        String encoded = extValue.substring(secondQuote + 1);
        Charset charset;
        try {
            charset = charsetName.isEmpty() ? StandardCharsets.UTF_8 : Charset.forName(charsetName);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            charset = StandardCharsets.UTF_8;
        }
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%' && i + 2 < encoded.length()) {
                int hi = Character.digit(encoded.charAt(i + 1), 16);
                int lo = Character.digit(encoded.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    decoded.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            decoded.write(c & 0xFF);
        }
        return decoded.toString(charset);
    }
}
