// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.identity.IdentityDetails
import io.cratis.arc.identity.IdentityDetailsProvider
import io.cratis.arc.identity.IdentityProviderContext
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

@SpringBootTest(classes = [ArcPlatformIdentityDisabledTests.Application::class], properties = ["cratis.arc.platform-identity.enabled=false"])
@AutoConfigureMockMvc
internal class ArcPlatformIdentityDisabledTests {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `disabled bridge ignores even otherwise valid platform identity headers`() {
        val initial = mvc.perform(get("/.cratis/me")
            .header(PLATFORM_HEADERS[0], Base64.getEncoder().encodeToString(PLATFORM_JSON.toByteArray()))
            .header(PLATFORM_HEADERS[1], "canonical-id").header(PLATFORM_HEADERS[2], "name")).andReturn()
        val response = mvc.perform(asyncDispatch(initial)).andReturn().response
        assertEquals(401, response.status)
        assertNull(response.getHeader("Set-Cookie"))
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
    class Application {
        @Bean fun noArtifacts(): ArcArtifactModule = object : ArcArtifactModule(emptyList(), emptyList()) {}
        @Bean fun unusedTrust(): ArcPlatformIdentityTrust = ArcPlatformIdentityTrust { error("Disabled bridge must not run") }
        @Bean fun unusedDetails(): IdentityDetailsProvider<String> = object : IdentityDetailsProvider<String> {
            override val detailsType = String::class.java
            override suspend fun provide(context: IdentityProviderContext): IdentityDetails<String> = error("Must remain anonymous")
        }
    }
}
