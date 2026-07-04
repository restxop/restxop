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

/**
 * The incoming message violates the wire format: missing or invalid outer
 * Content-Type parameters, a first part that is not the declared root,
 * missing part identifiers, or truncation before the closing delimiter.
 * The message states what was violated and where.
 */
public class MalformedMessageException extends RestxopException {

    private static final long serialVersionUID = 1L;

    public MalformedMessageException(String message) {
        super(message);
    }

    public MalformedMessageException(String message, Throwable cause) {
        super(null, message, cause);
    }

    public MalformedMessageException(String exchangeId, String message) {
        super(exchangeId, message);
    }

    public MalformedMessageException(String exchangeId, String message, Throwable cause) {
        super(exchangeId, message, cause);
    }
}
