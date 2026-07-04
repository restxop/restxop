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

import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Abstract wire-level conformance suite. Each starter's test module (and any
 * adopter with custom SPI implementations) extends this class and implements
 * the two codec hooks; the inherited tests then assert identical behavior
 * against the canonical fixtures byte-for-byte, which is how cross-generation
 * wire identity (SC-005) is proven.
 *
 * <p>The concrete conformance cases are attached per user story; this base
 * defines the binding surface and fixture access.</p>
 */
public abstract class RestxopConformanceSuite {

    /** Canonical fixture names bundled with the testkit. */
    protected static final String SINGLE_ATTACHMENT = "canonical/single-attachment.http";
    protected static final String MULTI_ATTACHMENT = "canonical/multi-attachment.http";
    protected static final String NESTED_ATTACHMENT = "canonical/nested-attachment.http";
    protected static final String NULL_ATTACHMENT = "canonical/null-attachment.http";
    protected static final String ZERO_ATTACHMENT = "canonical/zero-attachment.http";

    /** A produced wire message: outer Content-Type plus exact body bytes. */
    public record EncodedMessage(String contentType, byte[] body) {
    }

    /**
     * Serializes {@code payload} to a wire message through the
     * implementation under test, using the given deterministic boundary and
     * consuming attachment Content-IDs from {@code contentIds} in encounter
     * order (so output is fixture-comparable).
     */
    protected abstract EncodedMessage encode(Object payload, String boundary, List<String> contentIds);

    /**
     * Deserializes a wire message through the implementation under test into
     * a payload of {@code type}; returned attachments must be lazily
     * readable per the read-path contract.
     */
    protected abstract <T> T decode(String contentType, InputStream body, Type type);

    /** Loads a canonical fixture by name. */
    protected static WireFixture fixture(String name) {
        return Fixtures.load(name);
    }
}
