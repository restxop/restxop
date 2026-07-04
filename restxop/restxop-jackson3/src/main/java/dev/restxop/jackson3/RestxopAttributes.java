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
package dev.restxop.jackson3;

/**
 * Jackson context-attribute keys carrying per-call restxop state — the
 * collector on write and the resolver on read. Context attributes are the
 * Jackson-idiomatic per-call channel; restxop never uses thread-locals.
 */
public final class RestxopAttributes {

    /** Write-side {@code AttachmentCollector} attribute key. */
    public static final String COLLECTOR = "dev.restxop.collector";

    /** Read-side {@code AttachmentResolver} attribute key. */
    public static final String RESOLVER = "dev.restxop.resolver";

    private RestxopAttributes() {
    }
}
