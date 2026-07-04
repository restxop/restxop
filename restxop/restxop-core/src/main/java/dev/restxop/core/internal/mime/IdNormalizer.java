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
package dev.restxop.core.internal.mime;

import java.util.Locale;

/**
 * Content-ID / reference normalization per wire-format §4: strip one leading
 * {@code <} / trailing {@code >} pair if present, then one leading
 * {@code cid:} prefix (case-insensitive) if present. All identifier matching
 * (start ↔ root, href ↔ part) happens on normalized values.
 */
public final class IdNormalizer {

    private IdNormalizer() {
    }

    public static String normalize(String raw) {
        String id = raw.trim();
        if (id.length() >= 2 && id.charAt(0) == '<' && id.charAt(id.length() - 1) == '>') {
            id = id.substring(1, id.length() - 1);
        }
        if (id.toLowerCase(Locale.ROOT).startsWith("cid:")) {
            id = id.substring(4);
        }
        return id;
    }
}
