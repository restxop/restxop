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
 * The exchange failed as a whole — typically a transport or codec failure
 * mid-message. The causal error is attached and this exception is delivered
 * to every consumer pending or subsequently reading on the exchange.
 */
public class ExchangeFailedException extends RestxopException {

    private static final long serialVersionUID = 1L;

    public ExchangeFailedException(String exchangeId, String message, Throwable cause) {
        super(exchangeId, message, cause);
    }
}
