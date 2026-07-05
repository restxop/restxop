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

import dev.restxop.Attachment;
import dev.restxop.spi.AttachmentCollector;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Emits the XOP-style Include stub (wire-format §5) for every attachment
 * encountered during payload traversal, registering it with the exchange's
 * collector — this traversal IS the attachment discovery (constitution IV).
 */
final class AttachmentSerializer extends ValueSerializer<Attachment> {

    @Override
    public void serialize(Attachment value, JsonGenerator gen, SerializationContext ctxt) {
        Object attribute = ctxt.getAttribute(RestxopAttributes.COLLECTOR);
        if (!(attribute instanceof AttachmentCollector collector)) {
            ctxt.reportBadDefinition(Attachment.class,
                    "Attachment value outside a restxop exchange: no collector in the "
                            + "serialization context (write payloads through the restxop converter)");
            return;
        }
        String contentId = collector.register(value);
        boolean bare = collector instanceof dev.restxop.core.internal.write.ReferenceStyleAware aware
                && aware.bareReferences();
        gen.writeStartObject(value);
        gen.writeName("Include");
        gen.writeStartObject();
        gen.writeStringProperty("href", bare ? contentId : "cid:" + contentId);
        gen.writeEndObject();
        gen.writeEndObject();
    }
}
