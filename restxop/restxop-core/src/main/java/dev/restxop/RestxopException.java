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
package dev.restxop;

import java.util.Optional;

/**
 * Root of the restxop error hierarchy. Every failure surfaced by the library
 * is a subtype of this exception and carries the identifier of the message
 * exchange it occurred in (when one exists) for log correlation.
 */
public class RestxopException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String exchangeId;

    public RestxopException(String message) {
        this(null, message, null);
    }

    public RestxopException(String message, Throwable cause) {
        this(null, message, cause);
    }

    public RestxopException(String exchangeId, String message) {
        this(exchangeId, message, null);
    }

    public RestxopException(String exchangeId, String message, Throwable cause) {
        super(exchangeId == null ? message : "[exchange " + exchangeId + "] " + message, cause);
        this.exchangeId = exchangeId;
    }

    /** Identifier of the exchange this failure belongs to, when known. */
    public Optional<String> exchangeId() {
        return Optional.ofNullable(exchangeId);
    }
}
