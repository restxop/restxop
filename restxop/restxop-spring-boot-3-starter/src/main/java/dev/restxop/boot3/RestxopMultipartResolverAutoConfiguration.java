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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

/**
 * The servlet-resolver guard (research R6): Spring's default multipart
 * resolver historically claims every {@code multipart/*} request, consuming
 * {@code multipart/related} bodies before restxop can stream them. This
 * auto-configuration (default on; disable with
 * {@code restxop.strict-multipart-resolution=false}) contributes a
 * strict-compliance resolver that only handles {@code multipart/form-data},
 * so form uploads keep working while attachment messages pass through to
 * the restxop converter untouched.
 */
@AutoConfiguration(
        beforeName = "org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration")
@ConditionalOnClass(StandardServletMultipartResolver.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "restxop.strict-multipart-resolution",
        havingValue = "true", matchIfMissing = true)
public class RestxopMultipartResolverAutoConfiguration {

    /** Bean name fixed to {@code multipartResolver} so the DispatcherServlet finds it. */
    @Bean(name = "multipartResolver")
    @ConditionalOnMissingBean(MultipartResolver.class)
    public StandardServletMultipartResolver restxopStrictMultipartResolver() {
        StandardServletMultipartResolver resolver = new StandardServletMultipartResolver();
        resolver.setStrictServletCompliance(true);
        return resolver;
    }
}
