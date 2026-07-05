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

import java.io.IOException;

/**
 * Copy-avoiding facet of a part content stream: feeds the consumer directly
 * from the scanner's internal buffer instead of round-tripping through an
 * intermediate array (SC-006 hot path — one copy per transported byte
 * saved on the drain side).
 */
public interface BulkTransfer {

    /** Receives one contiguous chunk; must not retain the array. */
    @FunctionalInterface
    interface Sink {
        void accept(byte[] buf, int off, int len) throws IOException;
    }

    /**
     * Feeds the next available content chunk to {@code sink}, returning the
     * number of bytes fed, or -1 when the part is exhausted.
     */
    int transferNext(Sink sink) throws IOException;
}
