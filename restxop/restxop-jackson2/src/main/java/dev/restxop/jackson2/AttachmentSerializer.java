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
package dev.restxop.jackson2;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import dev.restxop.Attachment;
import dev.restxop.spi.AttachmentCollector;
import java.io.IOException;

/**
 * Emits the XOP-style Include stub (wire-format §5) for every attachment
 * encountered during payload traversal, registering it with the exchange's
 * collector — this traversal IS the attachment discovery (constitution IV).
 */
final class AttachmentSerializer extends JsonSerializer<Attachment> {

    @Override
    public void serialize(Attachment value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        Object attribute = provider.getAttribute(RestxopAttributes.COLLECTOR);
        if (!(attribute instanceof AttachmentCollector collector)) {
            throw JsonMappingException.from(gen,
                    "Attachment value outside a restxop exchange: no collector in the "
                            + "serialization context (write payloads through the restxop converter)");
        }
        String contentId = collector.register(value);
        boolean bare = collector instanceof dev.restxop.core.internal.write.ReferenceStyleAware aware
                && aware.bareReferences();
        gen.writeStartObject(value);
        gen.writeFieldName("Include");
        gen.writeStartObject();
        gen.writeStringField("href", bare ? contentId : "cid:" + contentId);
        gen.writeEndObject();
        gen.writeEndObject();
    }
}
