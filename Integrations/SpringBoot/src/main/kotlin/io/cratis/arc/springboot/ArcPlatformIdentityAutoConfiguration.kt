// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.authentication.AuthenticationConverter

/** Optional, default-off trusted platform-header security bridge; application chains remain authoritative. */
@AutoConfiguration(beforeName = ["org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration"])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.config.annotation.web.builders.HttpSecurity", "jakarta.servlet.Filter"])
@ConditionalOnProperty(prefix = "cratis.arc.platform-identity", name = ["enabled"], havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(ArcPlatformIdentityProperties::class)
public class ArcPlatformIdentityAutoConfiguration {
    /** Denies all ingress until the application supplies a deployment-specific policy. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcPlatformIdentityTrust(): ArcPlatformIdentityTrust = ArcPlatformIdentityTrust { false }

    /** Named backoff allows unrelated Spring authentication converters to coexist. */
    @Bean("arcPlatformAuthenticationConverter")
    @ConditionalOnMissingBean(name = ["arcPlatformAuthenticationConverter"])
    public fun arcPlatformAuthenticationConverter(trust: ArcPlatformIdentityTrust): AuthenticationConverter =
        ArcPlatformAuthenticationConverter(trust)

    /** Creates the security-chain filter, not a servlet-container filter. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcPlatformAuthenticationFilter(
        @Qualifier("arcPlatformAuthenticationConverter") converter: AuthenticationConverter
    ): ArcPlatformAuthenticationFilter = ArcPlatformAuthenticationFilter(converter)

    /** Prevents Boot from executing the filter a second time outside the security chain. */
    @Bean("arcPlatformAuthenticationFilterRegistration")
    @ConditionalOnMissingBean(name = ["arcPlatformAuthenticationFilterRegistration"])
    public fun arcPlatformAuthenticationFilterRegistration(filter: ArcPlatformAuthenticationFilter): FilterRegistrationBean<ArcPlatformAuthenticationFilter> =
        FilterRegistrationBean(filter).also { it.isEnabled = false }

    /**
     * With no application chain, require authentication everywhere, retain CSRF and session defaults,
     * and return 401 rather than a login redirect. Applications own any anonymous-route exceptions.
     */
    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain::class)
    public fun arcPlatformSecurityFilterChain(http: HttpSecurity, filter: ArcPlatformAuthenticationFilter): SecurityFilterChain {
        http.addFilterBefore(filter, AnonymousAuthenticationFilter::class.java)
        http.authorizeHttpRequests { it.anyRequest().authenticated() }
        http.exceptionHandling {
            it.authenticationEntryPoint { _, response, _ -> response.status = 401 }
            it.accessDeniedHandler { _, response, _ -> response.status = 403 }
        }
        return http.build()
    }
}
