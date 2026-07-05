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

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One captured wire message: the outer Content-Type header value and the
 * exact body bytes. Fixture bytes are normative — conformance assertions
 * compare against them byte-for-byte.
 *
 * @param name        resource-relative fixture name
 * @param contentType outer Content-Type header value
 * @param body        exact message body bytes (never mutated; callers copy)
 */
public record WireFixture(String name, String contentType, byte[] body) {

    private static final Pattern BOUNDARY = Pattern.compile(
            "boundary\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern START = Pattern.compile(
            "start\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);

    /** The boundary parameter of {@link #contentType()}. */
    public String boundary() {
        return param(BOUNDARY, "boundary");
    }

    /** The start parameter of {@link #contentType()} (raw, possibly bracketed). */
    public String start() {
        return param(START, "start");
    }

    private String param(Pattern pattern, String what) {
        Matcher matcher = pattern.matcher(contentType);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "fixture '" + name + "' has no " + what + " parameter: " + contentType);
        }
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WireFixture that
                && name.equals(that.name)
                && contentType.equals(that.contentType)
                && Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, contentType, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
        return "WireFixture[name=" + name + ", contentType=" + contentType
                + ", body=" + body.length + " bytes]";
    }
}
