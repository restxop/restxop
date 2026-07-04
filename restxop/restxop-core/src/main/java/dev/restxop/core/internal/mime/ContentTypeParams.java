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

import dev.restxop.MalformedMessageException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Parsed structured header value of the form
 * {@code type/subtype; name=value; name="quoted value"} — used for the outer
 * Content-Type and for Content-Disposition. Parameter names are matched
 * case-insensitively; values may be quoted (with backslash escapes) or bare
 * tokens; {@code --} sequences inside values are ordinary content.
 */
public final class ContentTypeParams {

    private final String mediaType;
    private final Map<String, String> parameters;

    private ContentTypeParams(String mediaType, Map<String, String> parameters) {
        this.mediaType = mediaType;
        this.parameters = parameters;
    }

    public static ContentTypeParams parse(String headerValue) {
        if (headerValue == null || headerValue.trim().isEmpty()) {
            throw new MalformedMessageException("missing Content-Type header value");
        }
        String value = headerValue.trim();
        int semicolon = value.indexOf(';');
        String mediaType = (semicolon < 0 ? value : value.substring(0, semicolon))
                .trim().toLowerCase(Locale.ROOT);
        if (mediaType.isEmpty()) {
            throw new MalformedMessageException("empty media type in Content-Type: " + headerValue);
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        if (semicolon >= 0) {
            parseParameters(value, semicolon + 1, parameters);
        }
        return new ContentTypeParams(mediaType, parameters);
    }

    private static void parseParameters(String value, int from, Map<String, String> out) {
        int i = from;
        int length = value.length();
        while (i < length) {
            while (i < length && (isWsp(value.charAt(i)) || value.charAt(i) == ';')) {
                i++;
            }
            if (i >= length) {
                return;
            }
            int equals = value.indexOf('=', i);
            if (equals < 0) {
                return; // trailing garbage without '=': ignore leniently
            }
            String name = value.substring(i, equals).trim().toLowerCase(Locale.ROOT);
            i = equals + 1;
            while (i < length && isWsp(value.charAt(i))) {
                i++;
            }
            String paramValue;
            if (i < length && value.charAt(i) == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < length && value.charAt(i) != '"') {
                    char c = value.charAt(i);
                    if (c == '\\' && i + 1 < length) {
                        i++;
                        c = value.charAt(i);
                    }
                    sb.append(c);
                    i++;
                }
                i++; // past closing quote (or end on unterminated quote)
                paramValue = sb.toString();
            } else {
                int end = value.indexOf(';', i);
                if (end < 0) {
                    end = length;
                }
                paramValue = value.substring(i, end).trim();
                i = end;
            }
            if (!name.isEmpty()) {
                out.putIfAbsent(name, paramValue);
            }
        }
    }

    private static boolean isWsp(char c) {
        return c == ' ' || c == '\t';
    }

    /** Lowercased {@code type/subtype}. */
    public String mediaType() {
        return mediaType;
    }

    /** Unquoted parameter value looked up case-insensitively. */
    public Optional<String> parameter(String name) {
        return Optional.ofNullable(parameters.get(name.toLowerCase(Locale.ROOT)));
    }

    /** Like {@link #parameter} but the parameter must exist. */
    public String requiredParameter(String name) {
        return parameter(name).orElseThrow(() -> new MalformedMessageException(
                "required Content-Type parameter '" + name.toLowerCase(Locale.ROOT)
                        + "' is missing (media type '" + mediaType + "')"));
    }
}
