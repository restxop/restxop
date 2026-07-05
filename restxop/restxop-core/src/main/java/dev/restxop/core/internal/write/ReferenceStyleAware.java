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
package dev.restxop.core.internal.write;

import dev.restxop.spi.AttachmentCollector;

/**
 * Optional facet of an {@link AttachmentCollector}: tells serializers which
 * reference style the message uses. Standard messages carry {@code cid:}
 * URIs (wire-format §5); the deprecated legacy compat mode carries bare
 * identifiers (§7).
 */
public interface ReferenceStyleAware {

    /** True when Include hrefs must be bare identifiers (legacy §7). */
    boolean bareReferences();
}
