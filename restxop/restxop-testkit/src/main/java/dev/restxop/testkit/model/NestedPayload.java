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

/** Payload with the attachment nested one object deep. */
public class NestedPayload {

    public String label;
    public Inner inner;

    public NestedPayload() {
    }

    public NestedPayload(String label, Inner inner) {
        this.label = label;
        this.inner = inner;
    }

    /** Child object carrying the attachment. */
    public static class Inner {

        public Attachment data;

        public Inner() {
        }

        public Inner(Attachment data) {
            this.data = data;
        }
    }
}
