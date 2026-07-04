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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;

/**
 * Framework-neutral carrier of the payload target type handed to a
 * {@link RootPartCodec}. Wraps a full generic {@link Type} so codecs can
 * resolve parameterized payloads (e.g. {@code Envelope<Report>}).
 */
public final class ResolvableTypeInfo {

    private final Type type;

    private ResolvableTypeInfo(Type type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    public static ResolvableTypeInfo of(Type type) {
        return new ResolvableTypeInfo(type);
    }

    /** The full generic type of the payload. */
    public Type type() {
        return type;
    }

    /** The erased class of {@link #type()}. */
    public Class<?> rawClass() {
        Type t = type;
        if (t instanceof ParameterizedType parameterized) {
            t = parameterized.getRawType();
        }
        if (t instanceof Class<?> clazz) {
            return clazz;
        }
        return Object.class;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ResolvableTypeInfo other && type.equals(other.type);
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }

    @Override
    public String toString() {
        return "ResolvableTypeInfo[" + type + "]";
    }
}
