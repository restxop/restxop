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

/**
 * Exchange lifecycle listener: the bridge from restxop's lifecycle events to
 * application metrics or auditing. All methods default to no-ops; exceptions
 * thrown by listener methods are logged and never propagated into the
 * exchange.
 */
public interface ExchangeListener {

    /** An exchange was opened (message read or write started). */
    default void exchangeStarted(ExchangeInfo info) {
    }

    /** The typed payload was delivered to the caller (read side). */
    default void payloadDelivered(ExchangeInfo info) {
    }

    /** An attachment stream was fully consumed. */
    default void attachmentConsumed(ExchangeInfo info, AttachmentInfo attachment) {
    }

    /** Bytes overflowed to spool storage for an attachment. */
    default void bytesSpooled(ExchangeInfo info, AttachmentInfo attachment, long totalSpooled) {
    }

    /** The exchange failed; {@code cause} is the causal error. */
    default void exchangeFailed(ExchangeInfo info, Throwable cause) {
    }

    /** The exchange ended and all its resources were released. */
    default void exchangeClosed(ExchangeInfo info) {
    }
}
