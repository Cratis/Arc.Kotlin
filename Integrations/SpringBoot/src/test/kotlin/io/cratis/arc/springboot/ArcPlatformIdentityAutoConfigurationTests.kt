// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.web.DefaultSecurityFilterChain
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AuthenticationConverter

internal class ArcPlatformIdentityAutoConfigurationTests {
    private val runner = WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(
        ArcPlatformIdentityAutoConfiguration::class.java,
        SecurityAutoConfiguration::class.java,
        ServletWebSecurityAutoConfiguration::class.java
    ))
    private val enabled = runner.withPropertyValues("cratis.arc.platform-identity.enabled=true")

    @Test
    fun `missing and false property leave bridge absent`() {
        for (properties in listOf(emptyArray(), arrayOf("cratis.arc.platform-identity.enabled=false"))) {
            runner.withPropertyValues(*properties).run { context ->
                assertNull(context.startupFailure)
                assertTrue(context.getBeansOfType(ArcPlatformAuthenticationFilter::class.java).isEmpty())
                assertFalse(context.containsBean("arcPlatformSecurityFilterChain"))
            }
        }
    }

    @Test
    fun `security absent stays supported even when enabled`() {
        enabled.withClassLoader(FilteredClassLoader("org.springframework.security")).run { context ->
            assertNull(context.startupFailure)
            assertFalse(context.containsBean("arcPlatformAuthenticationConverter"))
            assertFalse(context.containsBean("arcPlatformSecurityFilterChain"))
        }
    }

    @Test
    fun `enabled default denies ingress and disables servlet filter registration`() {
        enabled.run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.getBean(ArcPlatformIdentityProperties::class.java).isEnabled)
            assertEquals(1, context.getBeansOfType(SecurityFilterChain::class.java).size)
            val converter = context.getBean("arcPlatformAuthenticationConverter", AuthenticationConverter::class.java)
            assertThrows(BadCredentialsException::class.java) { converter.convert(platformRequest()) }
            val registration = context.getBean("arcPlatformAuthenticationFilterRegistration", FilterRegistrationBean::class.java)
            assertFalse(registration.isEnabled)
            assertSame(context.getBean(ArcPlatformAuthenticationFilter::class.java), registration.filter)
        }
    }

    @Test
    fun `application chain trust and named converter remain authoritative`() {
        val trust = ArcPlatformIdentityTrust { true }
        val converter = AuthenticationConverter { null }
        val chain = DefaultSecurityFilterChain({ false }, emptyList())
        enabled.withBean(ArcPlatformIdentityTrust::class.java, { trust })
            .withBean("arcPlatformAuthenticationConverter", AuthenticationConverter::class.java, { converter })
            .withBean(SecurityFilterChain::class.java, { chain }).run { context ->
                assertNull(context.startupFailure)
                assertSame(trust, context.getBean(ArcPlatformIdentityTrust::class.java))
                assertSame(converter, context.getBean("arcPlatformAuthenticationConverter"))
                assertSame(chain, context.getBean(SecurityFilterChain::class.java))
                assertFalse(context.containsBean("arcPlatformSecurityFilterChain"))
                assertTrue(chain.filters.isEmpty())
            }
    }

    @Test
    fun `custom filter backs off while unrelated converters do not disable platform converter`() {
        val filter = ArcPlatformAuthenticationFilter { null }
        enabled.withBean(ArcPlatformAuthenticationFilter::class.java, { filter })
            .withBean("otherConverter", AuthenticationConverter::class.java, { AuthenticationConverter { null } })
            .run { context ->
                assertNull(context.startupFailure)
                assertSame(filter, context.getBean(ArcPlatformAuthenticationFilter::class.java))
                assertTrue(context.getBean("arcPlatformAuthenticationConverter") is ArcPlatformAuthenticationConverter)
            }
    }
}
