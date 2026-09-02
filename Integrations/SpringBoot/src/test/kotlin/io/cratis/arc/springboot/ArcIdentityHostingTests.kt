// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import com.fasterxml.jackson.databind.ObjectMapper
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.identity.IdentityDetails
import io.cratis.arc.identity.IdentityDetailsProvider
import io.cratis.arc.identity.IdentityProviderContext
import java.security.Principal
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [ArcIdentityHostingTests.Application::class],
    properties = ["cratis.arc.tenancy.resolvers=claim"]
)
@AutoConfigureMockMvc
internal class ArcIdentityHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun resetProvider() {
        capturedContext.set(null)
    }

    @Test
    fun `authenticated identity returns exact body and matching client readable cookie`() {
        val authentication = authentication(
            ClaimsPrincipal(
                "Ada Lovelace",
                linkedMapOf(
                    "sub" to "user-42",
                    "department" to "engineering",
                    "groups" to listOf("one", "two"),
                    "tenant_id" to "tenant-42"
                )
            )
        )
        val result = execute(get(IDENTITY_ROUTE).principal(authentication))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("application/json;charset=UTF-8"))
            .andExpect(jsonPath("$.id").value("user-42"))
            .andExpect(jsonPath("$.name").value("Ada Lovelace"))
            .andExpect(jsonPath("$.isAuthenticated").value(true))
            .andExpect(jsonPath("$.isAuthorized").value(true))
            .andExpect(jsonPath("$.roles[0]").value("admin"))
            .andExpect(jsonPath("$.details.displayName").value("Ada Lovelace"))
            .andReturn()

        val cookieHeader = requireNotNull(result.response.getHeader("Set-Cookie"))
        assertThat(cookieHeader).startsWith(".cratis-identity=")
        assertThat(cookieHeader).contains("Path=/").contains("SameSite=Lax")
        assertThat(cookieHeader).doesNotContain("HttpOnly").contains("Secure")
        val encoded = cookieHeader.substringAfter('=').substringBefore(';')
        val decoded = Base64.getDecoder().decode(encoded)
        assertArrayEquals(result.response.contentAsByteArray, decoded)
        assertEquals(objectMapper.readTree(result.response.contentAsByteArray), objectMapper.readTree(decoded))

        val context = requireNotNull(capturedContext.get())
        assertEquals("user-42", context.id)
        assertEquals("Ada Lovelace", context.name)
        assertEquals(
            listOf(
                "sub" to "user-42",
                "department" to "engineering",
                "groups" to "one",
                "groups" to "two",
                "tenant_id" to "tenant-42"
            ),
            context.claims.map { it.type to it.value }
        )
    }

    @Test
    fun `anonymous identity is rejected without a cookie`() {
        mockMvc.perform(get(IDENTITY_ROUTE))
            .andExpect(request().asyncStarted())
            .andReturn()
            .let { initial -> mockMvc.perform(asyncDispatch(initial)) }
            .andExpect(status().isUnauthorized)
            .andExpect { result -> assertNull(result.response.getHeader("Set-Cookie")) }
        assertNull(capturedContext.get())
    }

    @Test
    fun `provider authorization rejection returns forbidden without a cookie`() {
        val authentication = authentication(ClaimsPrincipal("blocked", mapOf("sub" to "blocked-id")))
        execute(get(IDENTITY_ROUTE).principal(authentication))
            .andExpect(status().isForbidden)
            .andExpect { result -> assertNull(result.response.getHeader("Set-Cookie")) }
        assertNotNull(capturedContext.get())
    }

    @Test
    fun `secure request emits a secure client readable cookie`() {
        val authentication = authentication(ClaimsPrincipal("Ada", mapOf("sub" to "secure-user")))
        val result = execute(get(IDENTITY_ROUTE).secure(true).principal(authentication)).andExpect(status().isOk).andReturn()
        val cookieHeader = requireNotNull(result.response.getHeader("Set-Cookie"))
        assertThat(cookieHeader).contains("Secure").contains("SameSite=Lax")
        assertThat(cookieHeader).doesNotContain("HttpOnly")
    }

    @Test
    fun `identity details schema uses the Arc names and typed nullability`() {
        mockMvc.perform(get(SCHEMA_ROUTE))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("object"))
            .andExpect(jsonPath("$.properties.accessLevel.type").value("integer"))
            .andExpect(jsonPath("$.properties.displayName.type").value("string"))
            .andExpect(jsonPath("$.properties.tags.type").value("array"))
            .andExpect(jsonPath("$.properties.tags.items.type").value("string"))
            .andExpect(jsonPath("$.properties.nickname.type[0]").value("string"))
            .andExpect(jsonPath("$.properties.nickname.type[1]").value("null"))
            .andExpect(jsonPath("$.required").isArray)
    }

    private fun authentication(principal: Principal): UsernamePasswordAuthenticationToken =
        UsernamePasswordAuthenticationToken.authenticated(
            principal,
            "unused",
            listOf(SimpleGrantedAuthority("ROLE_admin"))
        )

    private fun execute(request: MockHttpServletRequestBuilder): ResultActions {
        val initial = mockMvc.perform(request).andExpect(request().asyncStarted()).andReturn()
        return mockMvc.perform(asyncDispatch(initial))
    }

    data class Details(
        val displayName: String,
        val accessLevel: Int,
        val tags: List<String>,
        val nickname: String?
    )

    class ClaimsPrincipal(private val principalName: String, val claims: Map<String, Any>) : Principal {
        override fun getName(): String = principalName
    }

    class TestIdentityDetailsProvider : IdentityDetailsProvider<Details> {
        override val detailsType: Class<Details> = Details::class.java

        override suspend fun provide(context: IdentityProviderContext): IdentityDetails<Details> {
            capturedContext.set(context)
            return IdentityDetails(
                context.name != "blocked",
                Details(context.name, 7, listOf("one"), null)
            )
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun emptyArtifactModule(): ArcArtifactModule = object : ArcArtifactModule(emptyList(), emptyList()) {}

        @Bean
        fun identityDetailsProvider(): IdentityDetailsProvider<Details> = TestIdentityDetailsProvider()
    }

    private companion object {
        const val IDENTITY_ROUTE = "/.cratis/me"
        const val SCHEMA_ROUTE = "/.cratis/identity-details/schema"
        val capturedContext = AtomicReference<IdentityProviderContext?>()
    }
}
