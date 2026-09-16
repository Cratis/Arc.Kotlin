// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authentication.AuthenticationFailureReason
import io.cratis.arc.authentication.AuthenticationHandler
import io.cratis.arc.authentication.AuthenticationResult
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.metadata.CommandDescriptor
import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean

@SpringBootTest(
    classes = [AuthenticationCorrelationApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["cratis.arc.coroutine-parallelism=1", "cratis.arc.coroutine-queue-capacity=0", "cratis.arc.overload-retry-after-seconds=17"]
)
internal class ArcAuthenticationCorrelationHostingTests : AuthenticationCorrelationChecks()

@SpringBootTest(
    classes = [AuthenticationCorrelationApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "cratis.arc.coroutine-parallelism=1", "cratis.arc.coroutine-queue-capacity=0",
        "cratis.arc.overload-retry-after-seconds=17", "cratis.arc.correlation-header=X-Application-Correlation"
    ]
)
internal class ArcAuthenticationCustomCorrelationHostingTests : AuthenticationCorrelationChecks()

internal abstract class AuthenticationCorrelationChecks {
    @Autowired lateinit var properties: ArcProperties
    @Autowired lateinit var scope: ArcApplicationCoroutineScope
    @Autowired lateinit var state: AuthenticationCorrelationState
    @LocalServerPort var port: Int = 0

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @BeforeEach
    fun reset() {
        state.entries.clear()
        state.authenticationCalls.set(0)
        state.commandCalls.set(0)
    }

    @ParameterizedTest
    @ValueSource(strings = ["valid", "missing", "malformed"])
    fun `anonymous rejection preserves established correlation`(input: String) = verify(input, "anonymous", 401)

    @ParameterizedTest
    @ValueSource(strings = ["valid", "missing", "malformed"])
    fun `credential rejection preserves established correlation`(input: String) = verify(input, "reject", 401)

    @ParameterizedTest
    @ValueSource(strings = ["valid", "missing", "malformed"])
    fun `authentication exception preserves established correlation`(input: String) = verify(input, "throw", 401)

    @ParameterizedTest
    @ValueSource(strings = ["valid", "missing", "malformed"])
    fun `admission rejection preserves established correlation and retry guidance`(input: String) = runBlocking {
        val reservation = checkNotNull(scope.tryLaunch(start = CoroutineStart.LAZY) { error("Reservation must never start") })
        try {
            verify(input, "reject", 503)
        } finally {
            reservation.cancelAndJoin()
        }
    }

    private fun verify(input: String, mode: String, status: Int) {
        val requestId = UUID.randomUUID().toString()
        val valid = "ABCDEFAB-1234-4567-89AB-ABCDEF012345"
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/correlation-auth"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("X-Test-Request", requestId)
            .header("X-Auth-Mode", mode)
            .POST(HttpRequest.BodyPublishers.ofString("{\"value\":\"test\"}"))
        when (input) {
            "valid" -> request.header(properties.correlationHeader, valid)
            "malformed" -> request.header(properties.correlationHeader, "not-a-uuid")
        }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(status, response.statusCode())
        assertEquals(if (status == 401) "Unauthorized" else "Service Unavailable", response.body())
        assertEquals("text/plain;charset=UTF-8", response.headers().firstValue("Content-Type").orElseThrow())
        assertTrue(response.headers().allValues("Content-Encoding").isEmpty())
        assertTrue(response.headers().allValues("ETag").isEmpty())
        assertTrue(response.headers().allValues("Content-Language").isEmpty())
        response.headers().firstValue("Content-Length").ifPresent { assertEquals(response.body().length, it.toInt()) }
        val established = state.entries[requestId]
        assertNotNull(established)
        val values = response.headers().allValues(properties.correlationHeader)
        assertEquals(listOf(established.toString()), values)
        assertEquals(values.single(), UUID.fromString(values.single()).toString())
        if (input == "valid") assertEquals(UUID.fromString(valid), established)
        if (properties.correlationHeader != "X-Correlation-ID") {
            assertTrue(response.headers().allValues("X-Correlation-ID").isEmpty())
        }
        assertEquals(0, state.commandCalls.get())
        assertEquals(if (status == 503) 0 else 1, state.authenticationCalls.get())
        assertEquals(if (status == 503) listOf("17") else emptyList(), response.headers().allValues("Retry-After"))
    }
}

internal class AuthenticationCorrelationState {
    val entries = ConcurrentHashMap<String, UUID>()
    val authenticationCalls = AtomicInteger()
    val commandCalls = AtomicInteger()
}

internal data class CorrelationAuthCommand(val value: String)

@SpringBootConfiguration
@EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
internal class AuthenticationCorrelationApplication {
    @Bean
    fun state(): AuthenticationCorrelationState = AuthenticationCorrelationState()

    @Bean
    fun captureEntry(state: AuthenticationCorrelationState): FilterRegistrationBean<Filter> =
        FilterRegistrationBean<Filter>(Filter { request, response, chain ->
            val httpRequest = request as HttpServletRequest
            val httpResponse = response as HttpServletResponse
            httpRequest.getHeader("X-Test-Request")?.let { requestId ->
                state.entries[requestId] = checkNotNull(ArcCorrelation.of(httpRequest))
                httpResponse.contentType = "application/problem+json"
                httpResponse.setContentLength(999)
                httpResponse.setHeader("Content-Encoding", "gzip")
                httpResponse.setHeader("ETag", "private-stale-tag")
                httpResponse.setHeader("Content-Language", "fr")
            }
            chain.doFilter(request, response)
        }).also {
            it.order = -105 // After correlation (-110), before authentication (-90).
            it.isAsyncSupported = true
            it.setDispatcherTypes(DispatcherType.REQUEST)
        }

    @Bean
    fun authenticationHandler(state: AuthenticationCorrelationState): AuthenticationHandler = AuthenticationHandler { context ->
        state.authenticationCalls.incrementAndGet()
        when (context.header("X-Auth-Mode")) {
            "throw" -> error("Private authentication exception")
            "reject" -> AuthenticationResult.failed(AuthenticationFailureReason.of("Private rejection reason"))
            else -> AuthenticationResult.ANONYMOUS
        }
    }

    @Bean
    fun module(state: AuthenticationCorrelationState): ArcArtifactModule = object : ArcArtifactModule(listOf(object : CommandHandler {
        override val commandType: Class<*> = CorrelationAuthCommand::class.java
        override val metadata = CommandDescriptor("CorrelationAuth", commandType.name, location = emptyList())
        override suspend fun invoke(context: CommandContext): Any {
            state.commandCalls.incrementAndGet()
            return (context.command as CorrelationAuthCommand).value
        }
    }), emptyList()) {}
}
