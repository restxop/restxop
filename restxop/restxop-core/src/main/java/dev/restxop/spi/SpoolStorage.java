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
package dev.restxop.spi;

import dev.restxop.RestxopConfig;
import java.io.IOException;

/**
 * Pluggable supplier of chase-buffer overflow storage. The default
 * implementation creates owner-only-permission files in the configured spool
 * directory; applications with stricter data-at-rest requirements substitute
 * their own strategy by contributing a bean/instance of this type.
 */
@FunctionalInterface
public interface SpoolStorage {

    /**
     * Creates overflow storage for one attachment part. Called at most once
     * per part, and only when its memory window first overflows.
     *
     * @param exchangeId the owning exchange (for naming/correlation)
     * @param contentId  the attachment part's normalized Content-ID
     * @param config     the exchange's configuration snapshot
     */
    OverflowStore createOverflow(String exchangeId, String contentId, RestxopConfig config) throws IOException;
}
