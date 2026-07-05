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
package dev.restxop.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.restxop.jackson2.Jackson2RootPartCodec;
import dev.restxop.spi.RootPartCodec;

/**
 * Executes the legacy-interoperability suite against the core engine with
 * the Jackson 2 codec (quickstart §7:
 * {@code mvn -pl restxop-testkit -Dgroups=legacy verify}).
 */
class LegacyCompatTest extends LegacyCompatSuite {

    private final Jackson2RootPartCodec codec = new Jackson2RootPartCodec(new ObjectMapper());

    @Override
    protected RootPartCodec codec() {
        return codec;
    }
}
