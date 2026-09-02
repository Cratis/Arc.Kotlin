// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import com.fasterxml.jackson.databind.ObjectMapper
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authentication.AuthenticationFailureReason
import io.cratis.arc.authentication.AuthenticationHandler
import io.cratis.arc.authentication.AuthenticationResult
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.identity.IdentityConstants
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.RouteOptions
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import jakarta.servlet.http.Cookie
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [ArcAuthenticationHostingTests.Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
internal class ArcAuthenticationHostingTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var principalFactory: ArcPrincipalFactory

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @LocalServerPort
    var port: Int = 0

    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    @BeforeEach
    fun reset() {
        Application.identityCookieObserved = false
    }

    @Test
    fun `configured authentication protects Arc endpoints with exact generic response`() {
        val initial = mockMvc.perform(post(SECURED_ROUTE).json("""{"value":"one"}"""))
            .andReturn()

        awaitAuthentication(initial, expectedUnauthorized = true)
        assertEquals(401, initial.response.status)
        assertEquals("Unauthorized", initial.response.contentAsString)
    }

    @Test
    fun `successful authentication principal is integrated into existing endpoint principal factory`() {
        val initial = mockMvc.perform(
            post(SECURED_ROUTE)
                .header("Authorization", "Bearer good")
                .json("""{"value":"one"}""")
        ).andExpect(request().asyncStarted()).andReturn()

        awaitAuthentication(initial)
        val captured = principalFactory.create(initial.request, listOf("admin"))
        assertEquals("alice", captured.name)
        assertEquals(setOf("admin"), captured.roles)
    }

    @Test
    fun `allow anonymous artifacts and literal introspection remain available`() {
        val anonymous = mockMvc.perform(post(ANONYMOUS_ROUTE).json("""{"value":"one"}"""))
            .andExpect(request().asyncStarted()).andReturn()
        awaitAuthentication(anonymous)
        assertNotNull(anonymous.request.getAttribute(ArcAuthenticationAttributes.RESULT))

        mockMvc.perform(get("/.cratis/commands").header("Authorization", "Bearer invalid"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").exists())
    }

    @Test
    fun `protected command and anonymous query sharing a path retain method specific security`() {
        val commandUnauthorized = send("POST", PROTECTED_COMMAND_ROUTE, body = """{"value":"one"}""")
        assertEquals(401, commandUnauthorized.statusCode())
        assertEquals("Unauthorized", commandUnauthorized.body())

        val anonymousQuery = send("GET", PROTECTED_COMMAND_ROUTE)
        assertEquals(200, anonymousQuery.statusCode())
        assertEquals(true, objectMapper.readTree(anonymousQuery.body()).path("isAuthorized").booleanValue())
        assertEquals("anonymous-query", objectMapper.readTree(anonymousQuery.body()).path("data").textValue())
        val anonymousRfcQuery = send(
            "QUERY",
            PROTECTED_COMMAND_ROUTE,
            body = """{"arguments":{},"paging":{},"sorting":{}}"""
        )
        assertEquals(200, anonymousRfcQuery.statusCode())
        assertEquals("anonymous-query", objectMapper.readTree(anonymousRfcQuery.body()).path("data").textValue())

        val forbiddenCommand = send(
            "POST",
            PROTECTED_COMMAND_ROUTE,
            authorization = "Bearer user",
            body = """{"value":"one"}"""
        )
        assertEquals(403, forbiddenCommand.statusCode())
        assertEquals(false, objectMapper.readTree(forbiddenCommand.body()).path("isAuthorized").booleanValue())
    }

    @Test
    fun `anonymous command and protected query sharing a path retain method specific security`() {
        val anonymousCommand = send("POST", ANONYMOUS_COMMAND_ROUTE, body = """{"value":"one"}""")
        assertEquals(200, anonymousCommand.statusCode())
        assertEquals(true, objectMapper.readTree(anonymousCommand.body()).path("isAuthorized").booleanValue())
        assertEquals("one", objectMapper.readTree(anonymousCommand.body()).path("response").textValue())

        val queryUnauthorized = send("GET", ANONYMOUS_COMMAND_ROUTE)
        assertEquals(401, queryUnauthorized.statusCode())
        assertEquals("Unauthorized", queryUnauthorized.body())

        val forbiddenQuery = send("GET", ANONYMOUS_COMMAND_ROUTE, "Bearer user")
        assertEquals(403, forbiddenQuery.statusCode())
        assertEquals(false, objectMapper.readTree(forbiddenQuery.body()).path("isAuthorized").booleanValue())

        val protectedQuery = send("GET", ANONYMOUS_COMMAND_ROUTE, "Bearer good")
        assertEquals(200, protectedQuery.statusCode())
        assertEquals(true, objectMapper.readTree(protectedQuery.body()).path("isAuthorized").booleanValue())
        assertEquals("protected-query", objectMapper.readTree(protectedQuery.body()).path("data").textValue())
    }

    @Test
    fun `identity cache cookie is never supplied as an authentication credential`() {
        val initial = mockMvc.perform(
            post(SECURED_ROUTE)
                .cookie(Cookie(IdentityConstants.IDENTITY_COOKIE_NAME, "forged"))
                .json("""{"value":"one"}""")
        ).andReturn()

        awaitAuthentication(initial, expectedUnauthorized = true)
        assertEquals(401, initial.response.status)
        assertFalse(Application.identityCookieObserved)
    }

    private fun send(
        method: String,
        path: String,
        authorization: String? = null,
        body: String? = null
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(5))
        if (authorization != null) request.header("Authorization", authorization)
        if (body != null) request.header("Content-Type", "application/json")
        return http.send(
            request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
    }

    private fun awaitAuthentication(result: MvcResult, expectedUnauthorized: Boolean = false) {
        repeat(200) {
            val completedAuthentication = result.request.getAttribute(ArcAuthenticationAttributes.RESULT) != null
            val completedResponse = !expectedUnauthorized ||
                result.response.status == 401 && result.response.contentAsString.isNotEmpty()
            if (completedAuthentication && completedResponse) return
            Thread.sleep(5)
        }
        throw AssertionError("Arc authentication did not complete.")
    }

    private fun MockHttpServletRequestBuilder.json(value: String): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(value)

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun javaFixtureModule(): ArcArtifactModule = JavaFixtureArcArtifactModule()

        @Bean
        fun mixedSecurityModule(): ArcArtifactModule = MixedSecurityModule()

        @Bean
        fun authenticationHandler(): AuthenticationHandler = AuthenticationHandler { context ->
            identityCookieObserved = context.cookies.containsKey(IdentityConstants.IDENTITY_COOKIE_NAME)
            when (context.header("Authorization")) {
                "Bearer good" -> AuthenticationResult.succeeded(
                    ArcPrincipal("alice", true, setOf("admin"), "alice")
                )
                "Bearer user" -> AuthenticationResult.succeeded(
                    ArcPrincipal("bob", true, emptySet(), "bob")
                )
                null -> AuthenticationResult.ANONYMOUS
                else -> AuthenticationResult.failed(AuthenticationFailureReason.of("Invalid credentials"))
            }
        }

        companion object {
            @Volatile
            var identityCookieObserved: Boolean = false
        }
    }

    private companion object {
        const val SECURED_ROUTE = "/api/fixtures/secured-command"
        const val ANONYMOUS_ROUTE = "/api/fixtures/java-fixture-command"
        const val PROTECTED_COMMAND_ROUTE = "/api/mixed/protected-mixed-command"
        const val ANONYMOUS_COMMAND_ROUTE = "/api/mixed/anonymous-mixed-command"
    }
}

internal data class ProtectedMixedCommand(val value: String)
internal data class AnonymousMixedCommand(val value: String)

private class MixedSecurityModule : ArcArtifactModule(
    listOf(
        MixedSecurityCommandHandler(
            ProtectedMixedCommand::class.java,
            "/api/mixed/protected-mixed-command",
            allowAnonymous = false
        ),
        MixedSecurityCommandHandler(
            AnonymousMixedCommand::class.java,
            "/api/mixed/anonymous-mixed-command",
            allowAnonymous = true
        )
    ),
    listOf(
        MixedSecurityQueryPerformer(
            "anonymous",
            "/api/mixed/protected-mixed-command",
            allowAnonymous = true,
            response = "anonymous-query"
        ),
        MixedSecurityQueryPerformer(
            "protected",
            "/api/mixed/anonymous-mixed-command",
            allowAnonymous = false,
            response = "protected-query"
        )
    )
)

private class MixedSecurityCommandHandler(
    override val commandType: Class<*>,
    path: String,
    allowAnonymous: Boolean
) : CommandHandler {
    override val metadata = CommandDescriptor(
        commandType.simpleName,
        commandType.name,
        routeOptions = RouteOptions(path),
        location = listOf("mixed"),
        authorization = securityMetadata(allowAnonymous),
        explicitPath = path,
        responseTypeName = String::class.java.name
    )

    override suspend fun invoke(context: CommandContext): Any = when (val command = context.command) {
        is ProtectedMixedCommand -> command.value
        is AnonymousMixedCommand -> command.value
        else -> error("Unsupported mixed-security command '${command.javaClass.name}'.")
    }
}

private class MixedSecurityQueryPerformer(
    name: String,
    path: String,
    allowAnonymous: Boolean,
    private val response: String
) : QueryPerformer {
    override val fullyQualifiedName = FullyQualifiedQueryName("io.cratis.arc.springboot.MixedSecurity.$name")
    override val descriptor = QueryDescriptor(
        name,
        "io.cratis.arc.springboot.MixedSecurity",
        String::class.java.name,
        routeOptions = RouteOptions(path),
        fullyQualifiedName = fullyQualifiedName.value,
        location = listOf("mixed"),
        authorization = securityMetadata(allowAnonymous),
        explicitPath = path
    )

    override suspend fun perform(context: QueryContext): Any = response
}

private fun securityMetadata(allowAnonymous: Boolean): AuthorizationMetadata = AuthorizationMetadata(
    allowAnonymous = allowAnonymous,
    roles = if (allowAnonymous) emptyList() else listOf("admin")
)
