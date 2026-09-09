// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import jakarta.servlet.DispatcherType
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean

/**
 * Runs ahead of Spring Security's `springSecurityFilterChain`, whose registration order is
 * `SecurityProperties.DEFAULT_FILTER_ORDER` (-100), and ahead of the Arc authentication filter
 * (-90), so a security rejection still carries correlation and Arc endpoints observe the identifier
 * the filter established. It stays at or below `OrderedFilter.REQUEST_WRAPPER_FILTER_MAX_ORDER` (0)
 * because the filter wraps the request.
 */
private const val ARC_CORRELATION_FILTER_ORDER = -110

/** Host-wide correlation identifier propagation for every servlet route, Arc-owned or not. */
@AutoConfiguration(before = [ArcWebAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["jakarta.servlet.Filter"])
@EnableConfigurationProperties(ArcProperties::class)
public class ArcCorrelationAutoConfiguration {
    /**
     * Establishes one correlation identifier for every request reaching the host.
     *
     * Set `cratis.arc.correlation-enabled` to `false` when the application owns correlation itself,
     * or define a bean named `arcCorrelationFilterRegistration` to replace the registration.
     */
    @Bean("arcCorrelationFilterRegistration")
    @ConditionalOnMissingBean(name = ["arcCorrelationFilterRegistration"])
    @ConditionalOnProperty(prefix = "cratis.arc", name = ["correlation-enabled"], matchIfMissing = true)
    internal fun arcCorrelationFilterRegistration(
        properties: ArcProperties
    ): FilterRegistrationBean<ArcCorrelationFilter> = FilterRegistrationBean(
        ArcCorrelationFilter(properties)
    ).also { registration ->
        registration.setName("arcCorrelationFilter")
        registration.order = ARC_CORRELATION_FILTER_ORDER
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC)
        registration.isAsyncSupported = true
        registration.addUrlPatterns("/*")
    }
}
