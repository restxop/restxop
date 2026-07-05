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
package dev.restxop.boot3;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.restxop.boot3.client.RestxopRestClientCustomizer;
import dev.restxop.boot3.client.RestxopRestTemplateCustomizer;
import dev.restxop.boot3.web.RestxopHttpMessageConverter;
import dev.restxop.core.internal.buffer.FileSpoolStorage;
import dev.restxop.jackson2.Jackson2RootPartCodec;
import dev.restxop.spi.ExchangeListener;
import dev.restxop.spi.RootPartCodec;
import dev.restxop.spi.SpoolStorage;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Boot 3.x auto-configuration: exchange capability activates by adding the
 * starter (FR-026). Application beans of type {@link ExchangeListener} and
 * {@link SpoolStorage} are picked up automatically.
 */
@AutoConfiguration
@EnableConfigurationProperties(RestxopProperties.class)
public class RestxopAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SpoolStorage restxopSpoolStorage() {
        return new FileSpoolStorage();
    }

    @Bean
    @ConditionalOnMissingBean
    public RootPartCodec restxopRootPartCodec(ObjectProvider<ObjectMapper> objectMapper) {
        return new Jackson2RootPartCodec(objectMapper.getIfAvailable(ObjectMapper::new));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public RestxopRuntime restxopRuntime(RestxopProperties properties, RootPartCodec codec,
            SpoolStorage spoolStorage, ObjectProvider<ExchangeListener> listeners) {
        List<ExchangeListener> listenerList = listeners.orderedStream().toList();
        return new RestxopRuntime(properties.toConfig(), codec, spoolStorage, listenerList);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestxopHttpMessageConverter restxopHttpMessageConverter(RestxopRuntime runtime) {
        return new RestxopHttpMessageConverter(runtime);
    }

    @Bean
    public RestxopRestTemplateCustomizer restxopRestTemplateCustomizer(
            RestxopHttpMessageConverter converter) {
        return new RestxopRestTemplateCustomizer(converter);
    }

    @Bean
    public RestxopRestClientCustomizer restxopRestClientCustomizer(
            RestxopHttpMessageConverter converter) {
        return new RestxopRestClientCustomizer(converter);
    }

    /**
     * Optional OpenFeign support (FR-027): a decoder with deferred close
     * plus a builder customizer that hands response lifetime to the decoder.
     * Nested so applications without Feign never introspect its types.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({feign.Feign.class,
        org.springframework.cloud.openfeign.FeignBuilderCustomizer.class})
    static class RestxopFeignConfiguration {

        @Bean
        @ConditionalOnMissingBean(feign.codec.Decoder.class)
        feign.codec.Decoder restxopFeignDecoder(RestxopRuntime runtime,
                ObjectProvider<org.springframework.boot.autoconfigure.http.HttpMessageConverters> converters) {
            feign.codec.Decoder springChain = new feign.optionals.OptionalDecoder(
                    new org.springframework.cloud.openfeign.support.ResponseEntityDecoder(
                            new org.springframework.cloud.openfeign.support.SpringDecoder(
                                    converters::getObject)));
            return new dev.restxop.boot3.feign.RestxopFeignDecoder(runtime, springChain);
        }

        @Bean
        org.springframework.cloud.openfeign.FeignBuilderCustomizer restxopFeignBuilderCustomizer() {
            // The restxop decoder owns response lifetime via deferred close,
            // so Feign must not close after decode
            return feign.Feign.Builder::doNotCloseAfterDecode;
        }
    }

    /**
     * Registers the converter with Spring MVC when running as a server.
     * Nested so non-web applications never introspect servlet types.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebMvcConfigurer.class)
    static class RestxopWebMvcConfiguration {

        @Bean
        WebMvcConfigurer restxopWebMvcConfigurer(RestxopHttpMessageConverter converter) {
            return new WebMvcConfigurer() {
                @Override
                public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                    converters.add(0, converter);
                }
            };
        }
    }
}
