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
 * A configured bound was exceeded: root part size, part header size, part
 * count, or a spool cap. Carries which limit fired and its configured value
 * so operators can tune deliberately.
 */
public class LimitExceededException extends RestxopException {

    private static final long serialVersionUID = 1L;

    private final String limitName;
    private final long configuredValue;

    public LimitExceededException(String exchangeId, String limitName, long configuredValue, String message) {
        super(exchangeId, message + " (limit '" + limitName + "' = " + configuredValue + ")");
        this.limitName = limitName;
        this.configuredValue = configuredValue;
    }

    /** Configuration property name of the limit that fired. */
    public String limitName() {
        return limitName;
    }

    /** The configured value of the violated limit. */
    public long configuredValue() {
        return configuredValue;
    }
}
