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

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Serialization adapter for the JSON root part. Implementations traverse the
 * payload with their serialization framework; attachment values are
 * discovered during that traversal (never by reflection field scanning) and
 * exchanged through the supplied collector/resolver callbacks.
 */
public interface RootPartCodec {

    /**
     * Whether this codec can (de)serialize the given payload type carrying
     * {@code Attachment} values. Implementations introspect the type for
     * reachable attachment properties and cache the verdict per type.
     */
    boolean canHandle(ResolvableTypeInfo type);

    /**
     * Serializes the payload as UTF-8 JSON to {@code out}, registering every
     * encountered attachment with {@code collector} and writing the returned
     * Content-IDs as XOP-style Include stubs.
     */
    void writeRoot(Object payload, OutputStream out, AttachmentCollector collector);

    /**
     * Deserializes the root part from {@code in}, resolving every Include
     * stub through {@code resolver} into an exchange-backed attachment.
     */
    Object readRoot(InputStream in, ResolvableTypeInfo type, AttachmentResolver resolver);
}
