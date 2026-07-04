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
package dev.restxop.boot3.client;

import dev.restxop.boot3.web.RestxopHttpMessageConverter;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.web.client.RestTemplate;

/**
 * Installs restxop on {@link RestTemplate}s built through the Boot
 * {@code RestTemplateBuilder}: the attachment-message converter plus the
 * deferred-close request-factory wrapper (FR-024) — no user plumbing for
 * connection lifecycle.
 */
public class RestxopRestTemplateCustomizer implements RestTemplateCustomizer {

    private final RestxopHttpMessageConverter converter;

    public RestxopRestTemplateCustomizer(RestxopHttpMessageConverter converter) {
        this.converter = converter;
    }

    @Override
    public void customize(RestTemplate restTemplate) {
        restTemplate.getMessageConverters().add(0, converter);
        if (!(restTemplate.getRequestFactory() instanceof DeferredCloseClientHttpRequestFactory)) {
            restTemplate.setRequestFactory(
                    new DeferredCloseClientHttpRequestFactory(restTemplate.getRequestFactory()));
        }
    }
}
