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
import dev.restxop.spi.AttachmentResolver;
import dev.restxop.spi.ResolvableTypeInfo;
import dev.restxop.spi.RootPartCodec;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.introspect.BeanPropertyDefinition;
import tools.jackson.databind.introspect.ClassIntrospector;

/**
 * {@link RootPartCodec} on Jackson 3.x (tools.jackson). Per-call state
 * (collector/resolver) travels via Jackson context attributes; the codec
 * works on a rebuilt copy of the application's mapper with the restxop
 * module registered, so application serialization configuration applies to
 * root parts while the app's own mapper stays untouched.
 */
public final class Jackson3RootPartCodec implements RootPartCodec {

    private final ObjectMapper mapper;
    private final Map<ResolvableTypeInfo, Boolean> canHandleCache = new ConcurrentHashMap<>();

    public Jackson3RootPartCodec(ObjectMapper applicationMapper) {
        // Jackson 3 sorts properties alphabetically by default; wire output
        // must stay declaration-ordered so both platform generations produce
        // byte-identical messages (SC-005)
        this.mapper = applicationMapper.rebuild()
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .addModule(new RestxopJackson3Module())
                .build();
    }

    @Override
    public boolean canHandle(ResolvableTypeInfo type) {
        return canHandleCache.computeIfAbsent(type,
                t -> reachesAttachment(mapper.constructType(t.type()), new HashSet<>(), 0));
    }

    /**
     * Jackson type introspection (research R5): walks the property graph the
     * serializer would traverse, looking for a reachable Attachment — never
     * reflection field scanning.
     */
    private boolean reachesAttachment(JavaType type, Set<Class<?>> visiting, int depth) {
        if (depth > 24) {
            return false;
        }
        if (type.isTypeOrSubTypeOf(Attachment.class)) {
            return true;
        }
        if (type.isContainerType()) {
            JavaType keyType = type.getKeyType();
            if (keyType != null && reachesAttachment(keyType, visiting, depth + 1)) {
                return true;
            }
            JavaType contentType = type.getContentType();
            return contentType != null && reachesAttachment(contentType, visiting, depth + 1);
        }
        Class<?> raw = type.getRawClass();
        if (raw.isPrimitive() || raw.getName().startsWith("java.") || raw.isEnum()) {
            return false;
        }
        if (!visiting.add(raw)) {
            return false; // cycle
        }
        try {
            SerializationConfig config = mapper.serializationConfig();
            ClassIntrospector introspector = config.classIntrospectorInstance().forOperation(config);
            BeanDescription description = introspector.introspectForSerialization(type,
                    introspector.introspectClassAnnotations(type));
            for (BeanPropertyDefinition property : description.findProperties()) {
                JavaType propertyType = property.getPrimaryType();
                if (propertyType != null && reachesAttachment(propertyType, visiting, depth + 1)) {
                    return true;
                }
            }
            return false;
        } finally {
            visiting.remove(raw);
        }
    }

    @Override
    public void writeRoot(Object payload, OutputStream out, AttachmentCollector collector) {
        mapper.writer()
                .withAttribute(RestxopAttributes.COLLECTOR, collector)
                .writeValue(out, payload);
    }

    @Override
    public Object readRoot(InputStream in, ResolvableTypeInfo type, AttachmentResolver resolver) {
        return mapper.readerFor(mapper.constructType(type.type()))
                .withAttribute(RestxopAttributes.RESOLVER, resolver)
                .readValue(in);
    }
}
