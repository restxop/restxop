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

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import dev.restxop.Attachment;
import dev.restxop.spi.AttachmentCollector;
import dev.restxop.spi.AttachmentResolver;
import dev.restxop.spi.ResolvableTypeInfo;
import dev.restxop.spi.RootPartCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link RootPartCodec} on Jackson 2.x. Per-call state (collector/resolver)
 * travels via Jackson context attributes; the codec works on a private copy
 * of the application's mapper with the restxop module registered, so
 * application serialization configuration (naming, inclusion, dates)
 * applies to root parts while the app's own mapper stays untouched.
 */
public final class Jackson2RootPartCodec implements RootPartCodec {

    private final ObjectMapper mapper;
    private final Map<ResolvableTypeInfo, Boolean> canHandleCache = new ConcurrentHashMap<>();

    public Jackson2RootPartCodec(ObjectMapper applicationMapper) {
        this.mapper = applicationMapper.copy().registerModule(new RestxopJackson2Module());
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
            BeanDescription description = mapper.getSerializationConfig().introspect(type);
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
        try {
            mapper.writer()
                    .withAttribute(RestxopAttributes.COLLECTOR, collector)
                    .writeValue(out, payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Object readRoot(InputStream in, ResolvableTypeInfo type, AttachmentResolver resolver) {
        try {
            return mapper.readerFor(mapper.constructType(type.type()))
                    .withAttribute(RestxopAttributes.RESOLVER, resolver)
                    .readValue(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
