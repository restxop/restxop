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

import dev.restxop.Attachment;

/**
 * Write-side callback handed to a {@link RootPartCodec}: the codec calls
 * {@link #register} for every attachment value it encounters during payload
 * traversal and embeds the returned Content-ID in the reference stub.
 *
 * <p>Implementations deduplicate by instance identity: registering the same
 * {@code Attachment} instance twice returns the same Content-ID and the part
 * is transmitted once.</p>
 */
@FunctionalInterface
public interface AttachmentCollector {

    /**
     * Registers an attachment encountered during serialization.
     *
     * @return the normalized Content-ID assigned to (or already held by) the
     *         attachment's MIME part
     */
    String register(Attachment attachment);
}
