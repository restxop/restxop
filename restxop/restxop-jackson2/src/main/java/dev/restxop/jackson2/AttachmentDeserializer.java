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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import dev.restxop.Attachment;
import dev.restxop.core.internal.mime.IdNormalizer;
import dev.restxop.spi.AttachmentResolver;
import java.io.IOException;

/**
 * Materializes Include stubs (wire-format §5) into exchange-backed lazy
 * attachments via the exchange's resolver; references are wired here, at
 * payload deserialization time, not when parts arrive (FR-016).
 */
final class AttachmentDeserializer extends JsonDeserializer<Attachment> {

    @Override
    public Attachment deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        Object attribute = context.getAttribute(RestxopAttributes.RESOLVER);
        if (!(attribute instanceof AttachmentResolver resolver)) {
            return (Attachment) context.reportBadDefinition(Attachment.class,
                    "Attachment value outside a restxop exchange: no resolver in the "
                            + "deserialization context (read payloads through the restxop converter)");
        }
        JsonNode node = parser.readValueAsTree();
        JsonNode href = node.path("Include").path("href");
        if (!href.isTextual() || href.asText().isEmpty()) {
            return (Attachment) context.reportInputMismatch(Attachment.class,
                    "attachment reference is not an Include stub with a textual href: %s", node);
        }
        return resolver.resolve(IdNormalizer.normalize(href.asText()));
    }
}
