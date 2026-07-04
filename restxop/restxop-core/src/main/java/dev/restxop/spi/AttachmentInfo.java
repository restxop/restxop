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

import java.util.Optional;

/** Read-only view of one attachment part handed to {@link ExchangeListener} methods. */
public interface AttachmentInfo {

    /** Normalized Content-ID of the part. */
    String contentId();

    /** Filename from the part's Content-Disposition, when present. */
    Optional<String> filename();

    /** Content type from the part's Content-Type header, when present. */
    Optional<String> contentType();
}
