// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.ExceptionDetailRedactor
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.identity.AsyncUsersProvider
import io.cratis.arc.identity.User
import io.cratis.arc.identity.UsersProvider
import io.cratis.arc.tenancy.AsyncTenantsProvider
import io.cratis.arc.tenancy.Tenant
import io.cratis.arc.tenancy.TenantId
import io.cratis.arc.tenancy.TenantName
import io.cratis.arc.tenancy.TenantsProvider
import java.util.concurrent.CompletableFuture
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [ArcDevelopmentEndpointsHostingTests.Application::class])
@AutoConfigureMockMvc
internal class ArcDevelopmentEndpointsHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `users and tenants aggregate ordered coroutine and Java async providers and deduplicate`() {
        execute(get(USERS_ROUTE))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].principal.id").value("one"))
            .andExpect(jsonPath("$[0].details.source").value("coroutine"))
            .andExpect(jsonPath("$[1].principal.id").value("two"))

        execute(get(TENANTS_ROUTE))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[0].id").value("one"))
            .andExpect(jsonPath("$[0].name").value("First"))
            .andExpect(jsonPath("$[1].id").value("two"))
    }

    private fun execute(request: MockHttpServletRequestBuilder): ResultActions {
        val initial = mockMvc.perform(request).andExpect(request().asyncStarted()).andReturn()
        return mockMvc.perform(asyncDispatch(initial))
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun emptyArtifactModule(): ArcArtifactModule = object : ArcArtifactModule(emptyList(), emptyList()) {}

        @Bean
        @Order(1)
        fun users(): UsersProvider = UsersProvider {
            listOf(user("one", mapOf("source" to "coroutine")))
        }

        @Bean
        @Order(2)
        fun asyncUsers(): AsyncUsersProvider = AsyncUsersProvider {
            CompletableFuture.completedFuture(listOf(user("one"), user("two")))
        }

        @Bean
        @Order(1)
        fun tenants(): TenantsProvider = TenantsProvider {
            listOf(tenant("one", "First"))
        }

        @Bean
        @Order(2)
        fun asyncTenants(): AsyncTenantsProvider = AsyncTenantsProvider {
            CompletableFuture.completedFuture(listOf(tenant("one", "Duplicate"), tenant("two", "Second")))
        }
    }

    private companion object {
        fun user(id: String, details: Any? = null) = User(ArcPrincipal(id, true, emptySet(), id), details)
        fun tenant(id: String, name: String) = Tenant(TenantId.of(id), TenantName(name))
    }
}

@SpringBootTest(classes = [ArcDevelopmentEndpointsEmptyHostingTests.Application::class])
@AutoConfigureMockMvc
internal class ArcDevelopmentEndpointsEmptyHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `development endpoints return empty arrays without providers and allow anonymous callers`() {
        listOf(USERS_ROUTE, TENANTS_ROUTE).forEach { route ->
            val initial = mockMvc.perform(get(route)).andExpect(request().asyncStarted()).andReturn()
            mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk)
                .andExpect(content().json("[]"))
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun emptyArtifactModule(): ArcArtifactModule = object : ArcArtifactModule(emptyList(), emptyList()) {}
    }
}

@SpringBootTest(classes = [ArcDevelopmentEndpointsErrorHostingTests.Application::class])
@AutoConfigureMockMvc
internal class ArcDevelopmentEndpointsErrorHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `provider failures are redacted`() {
        val initial = mockMvc.perform(get(USERS_ROUTE)).andExpect(request().asyncStarted()).andReturn()
        mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.error").value(ExceptionDetailRedactor.REDACTED_MESSAGE))
            .andExpect(content().string(not(containsString("secret-provider-error"))))
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun emptyArtifactModule(): ArcArtifactModule = object : ArcArtifactModule(emptyList(), emptyList()) {}

        @Bean
        fun failingUsers(): UsersProvider = UsersProvider { error("secret-provider-error") }
    }
}
