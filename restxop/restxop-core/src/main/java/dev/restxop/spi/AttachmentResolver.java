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
 * Read-side callback handed to a {@link RootPartCodec}: the codec calls
 * {@link #resolve} for every reference stub it materializes during payload
 * deserialization and places the returned attachment in the object graph.
 *
 * <p>Implementations return the same instance for the same normalized
 * Content-ID, so duplicate references resolve to one shared attachment.</p>
 */
@FunctionalInterface
public interface AttachmentResolver {

    /**
     * Resolves a reference to an exchange-backed lazy attachment.
     *
     * @param contentId the normalized Content-ID from the reference stub
     */
    Attachment resolve(String contentId);
}
