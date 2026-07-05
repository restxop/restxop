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
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.web.client.RestClient;

/**
 * Installs restxop on {@link RestClient}s built through the auto-configured
 * builder: the attachment-message converter plus the deferred-close request
 * factory (FR-024) around the classpath-detected default. Applications that
 * set their own request factory must wrap it in
 * {@link DeferredCloseClientHttpRequestFactory} to keep deferred close.
 */
public class RestxopRestClientCustomizer implements RestClientCustomizer {

    private final RestxopHttpMessageConverter converter;

    public RestxopRestClientCustomizer(RestxopHttpMessageConverter converter) {
        this.converter = converter;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void customize(RestClient.Builder builder) {
        builder.messageConverters(converters -> {
            if (!converters.contains(converter)) {
                converters.add(0, converter);
            }
        });
        builder.requestFactory(new DeferredCloseClientHttpRequestFactory(
                ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS)));
    }
}
