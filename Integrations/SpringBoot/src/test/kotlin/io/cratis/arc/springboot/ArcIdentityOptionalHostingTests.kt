// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.identity.AsyncIdentityDetailsProvider
import java.security.Principal
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [ArcIdentitySchemaAbsentHostingTests.Application::class])
@AutoConfigureMockMvc
internal class ArcIdentitySchemaAbsentHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `schema is empty and identity endpoint is absent without a provider`() {
        mockMvc.perform(get(SCHEMA_ROUTE))
            .andExpect(status().isOk)
            .andExpect(content().string("{}"))
        mockMvc.perform(get(IDENTITY_ROUTE)).andExpect(status().isNotFound)
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun emptyArtifactModule(): ArcArtifactModule = object : ArcArtifactModule(emptyList(), emptyList()) {}
    }
}

@SpringBootTest(classes = [ArcAsyncIdentityHostingTests.Application::class])
@AutoConfigureMockMvc
internal class ArcAsyncIdentityHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `Java asynchronous provider is adapted without blocking`() {
        val authentication = UsernamePasswordAuthenticationToken.authenticated(
            Principal { "Java Ada" },
            "unused",
            emptyList()
        )
        val initial = mockMvc.perform(get(IDENTITY_ROUTE).principal(authentication))
            .andExpect(request().asyncStarted())
            .andReturn()
        mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Java Ada"))
            .andExpect(jsonPath("$.details.displayName").value("Java Ada"))
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun emptyArtifactModule(): ArcArtifactModule = object : ArcArtifactModule(emptyList(), emptyList()) {}

        @Bean
        fun asyncIdentityDetailsProvider(): AsyncIdentityDetailsProvider<JavaAsyncIdentityDetailsProvider.Details> =
            JavaAsyncIdentityDetailsProvider()
    }
}

private const val IDENTITY_ROUTE = "/.cratis/me"
private const val SCHEMA_ROUTE = "/.cratis/identity-details/schema"
