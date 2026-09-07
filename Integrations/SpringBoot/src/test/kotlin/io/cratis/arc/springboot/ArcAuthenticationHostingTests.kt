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
import jakarta.servlet.AsyncContext
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequestWrapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.mock.web.MockAsyncContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.web.servlet.MockMvc
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

        val captured = principalFactory.create(initial.request, listOf("admin"))
        assertEquals("alice", captured.name)
        assertEquals(setOf("admin"), captured.roles)
    }

    @Test
    fun `allow anonymous artifacts and literal introspection remain available`() {
        val anonymous = mockMvc.perform(post(ANONYMOUS_ROUTE).json("""{"value":"one"}"""))
            .andExpect(request().asyncStarted()).andReturn()
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

    private fun MockHttpServletRequestBuilder.json(value: String): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(value)

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        /**
         * Ordered ahead of the Arc authentication filter so that it wraps it. See
         * [MockMvcAsyncSettlingFilter] for why MockMvc requests have to be settled inside the chain.
         */
        @Bean
        fun mockMvcAsyncSettlingFilterRegistration(): FilterRegistrationBean<Filter> =
            FilterRegistrationBean<Filter>(MockMvcAsyncSettlingFilter()).also { registration ->
                registration.setName("mockMvcAsyncSettlingFilter")
                registration.order = MOCK_MVC_ASYNC_SETTLING_FILTER_ORDER
                registration.setDispatcherTypes(DispatcherType.REQUEST)
                registration.addUrlPatterns("/*")
            }

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

/**
 * Keeps MockMvc requests single-threaded for the tests that observe them.
 *
 * MockMvc runs the filter chain on the calling thread, so `ArcAuthenticationFilter` starts async
 * processing and returns while its `arc-work-*` coroutine is still writing the request attributes and
 * the response. Neither `MockHttpServletRequest` nor `MockHttpServletResponse` is thread-safe, and
 * `MockMvc.perform` reads both before it returns: Spring Boot's print handler is registered with
 * `alwaysDo`, formats the request and the response eagerly, and only defers *writing* the lines until a
 * failure. Iterating the response headers there while the filter's `response.reset()` clears them is
 * what produced the `ConcurrentModificationException` in #107, and the `NullPointerException` inside
 * `HeaderValueHolder.getStringValues` seen on another branch.
 *
 * This filter closes the window instead of racing it: it does not return from the chain until the async
 * request has settled, so every MockMvc read — the print handler, the result matchers, and the test
 * body — happens after the container thread is finished. Waiting here is safe because the coroutine runs
 * on the scope's own `arc-work-*` pool, never on the request thread.
 *
 * The listener is registered from inside `startAsync`, on the request thread, before the filter can
 * launch its coroutine. Registering it afterwards would be the same class of bug: `complete()` iterates
 * the listener list on the coroutine thread, so adding to it from the test thread is exactly the
 * unsynchronized mutation this is meant to remove. `dispatch()` never notifies listeners, so the
 * dispatch handler is the other half of the signal; `MockAsyncContext` registers it under the same
 * monitor `dispatch()` holds and runs it immediately when the dispatch already happened, which is what
 * makes both orderings safe and gives the happens-before edge the test then reads across.
 *
 * Real-server requests made through the `send` helper never reach any of this: they are not
 * `MockHttpServletRequest`s, and blocking their container thread would deadlock the async dispatch.
 */
private class MockMvcAsyncSettlingFilter : Filter {
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        if (request !is MockHttpServletRequest || request.dispatcherType != DispatcherType.REQUEST) {
            chain.doFilter(request, response)
            return
        }
        val settling = SettlingRequest(request)
        chain.doFilter(settling, response)
        settling.awaitSettled()
    }

    private class SettlingRequest(request: MockHttpServletRequest) : HttpServletRequestWrapper(request) {
        private val settled = CountDownLatch(1)

        /** Only ever written and read on the request thread, which is where `startAsync` is called. */
        private var startedAsync = false

        override fun startAsync(): AsyncContext = super.startAsync().also(::track)

        override fun startAsync(servletRequest: ServletRequest, servletResponse: ServletResponse): AsyncContext =
            super.startAsync(servletRequest, servletResponse).also(::track)

        fun awaitSettled() {
            if (!startedAsync) return
            check(settled.await(SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Arc authentication neither dispatched nor completed within $SETTLE_TIMEOUT_SECONDS seconds."
            }
        }

        private fun track(context: AsyncContext) {
            startedAsync = true
            context.addListener(object : AsyncListener {
                override fun onComplete(event: AsyncEvent) = settled.countDown()
                override fun onTimeout(event: AsyncEvent) = settled.countDown()
                override fun onError(event: AsyncEvent) = settled.countDown()
                override fun onStartAsync(event: AsyncEvent) = Unit
            })
            (context as MockAsyncContext).addDispatchHandler { settled.countDown() }
        }
    }
}

private const val MOCK_MVC_ASYNC_SETTLING_FILTER_ORDER = -100
private const val SETTLE_TIMEOUT_SECONDS = 30L
