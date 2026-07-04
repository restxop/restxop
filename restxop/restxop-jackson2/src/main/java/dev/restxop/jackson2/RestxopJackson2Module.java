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

import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.restxop.Attachment;

/**
 * Jackson 2 module registering the {@link Attachment} (de)serializers.
 * {@link Jackson2RootPartCodec} registers it automatically on its private
 * mapper copy; register it manually only when serializing restxop payload
 * types through your own mapper for non-wire purposes.
 */
public final class RestxopJackson2Module extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public RestxopJackson2Module() {
        super("restxop-jackson2");
        addSerializer(Attachment.class, new AttachmentSerializer());
        addDeserializer(Attachment.class, new AttachmentDeserializer());
    }
}
