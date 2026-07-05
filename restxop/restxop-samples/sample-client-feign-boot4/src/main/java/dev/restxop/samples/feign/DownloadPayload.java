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
package dev.restxop.samples.feign;

import dev.restxop.Attachment;

/**
 * Typed download response: ordinary JSON fields plus one streamed
 * attachment. The seed lets clients regenerate the expected content for
 * checksum verification.
 */
public class DownloadPayload {

    public String name;
    public long seed;
    public long size;
    public Attachment data;

    public DownloadPayload() {
    }

    public DownloadPayload(String name, long seed, long size, Attachment data) {
        this.name = name;
        this.seed = seed;
        this.size = size;
        this.data = data;
    }
}
