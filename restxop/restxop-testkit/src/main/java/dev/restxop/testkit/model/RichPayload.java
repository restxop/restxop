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
package dev.restxop.testkit.model;

import dev.restxop.Attachment;
import java.util.List;
import java.util.Map;

/**
 * Realistic object graph for the fidelity suite: attachments nested one
 * level deep, in a list, in map values, inherited from the base class, and
 * an optional (null) attachment field — all discovered by serializer
 * traversal, never by field scanning (constitution IV).
 */
public class RichPayload extends RichPayloadBase {

    public String label;
    public NestedPayload.Inner inner;
    public List<Attachment> items;
    public Map<String, Attachment> byName;
    public Attachment missing;

    public RichPayload() {
        // codec instantiation
    }
}
