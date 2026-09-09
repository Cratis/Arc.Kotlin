// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import tools.jackson.databind.ObjectMapper
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered

/**
 * Every Arc endpoint reports the correlation identifier the host established for the request.
 *
 * The host-wide correlation filter resolves one identifier per request and presents it to the rest
 * of the chain, so an endpoint that resolves its own would silently reintroduce the divergence the
 * filter exists to remove. A recording filter runs last in the chain, immediately before Spring
 * MVC dispatches, and reports what the host established as a separate response header. Each Arc
 * endpoint then has to agree with it - both in the correlation response header it writes and in the
 * `correlationId` of the result envelope it returns.
 */
@SpringBootTest(
    classes = [ArcEndpointCorrelationTests.Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
internal class ArcEndpointCorrelationTests {
    @Autowired
    lateinit var objectMapper: ObjectMapper

    @LocalServerPort
    var port: Int = 0

    private val probe by lazy { EndpointProbe(port) }

    @Test
    fun `no Arc endpoint mints a correlation identifier of its own`() {
        ARC_ENDPOINTS.forEach { endpoint ->
            val response = probe.send(endpoint)

            assertHandled(endpoint, response)
            val established = establishedCorrelation(response)
            UUID.fromString(established)
            assertEquals(
                established,
                correlationHeader(response),
                "${endpoint.name} echoed a correlation identifier the host did not establish."
            )
            if (endpoint.reportsCorrelationInBody) {
                assertEquals(
                    established,
                    objectMapper.readTree(response.body()).path("correlationId").stringValue(),
                    "${endpoint.name} reported a correlation identifier the host did not establish."
                )
            }
        }
    }

    @Test
    fun `every Arc endpoint keeps the correlation identifier the client supplied`() {
        ARC_ENDPOINTS.forEach { endpoint ->
            val supplied = UUID.randomUUID().toString()

            val response = probe.send(endpoint, supplied)

            assertHandled(endpoint, response)
            assertEquals(supplied, establishedCorrelation(response), "${endpoint.name}: the host replaced a valid identifier.")
            assertEquals(supplied, correlationHeader(response), "${endpoint.name} echoed a different correlation identifier.")
        }
    }

    /** Hosts the Arc fixtures next to a filter that reports what the host established. */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
    class Application {
        /**
         * Reports the correlation identifier the host established, as an ordinary application filter
         * would observe it.
         *
         * It runs at the end of the chain so that it sees exactly what Spring MVC dispatches to an
         * Arc endpoint, whether or not the Arc correlation filter is registered.
         */
        @Bean
        fun establishedCorrelationRecorder(): FilterRegistrationBean<Filter> = FilterRegistrationBean<Filter>(
            Filter { request, response, chain ->
                (response as HttpServletResponse).setHeader(
                    ESTABLISHED_CORRELATION_HEADER,
                    ArcCorrelation.of(request as HttpServletRequest)?.toString() ?: NOTHING_ESTABLISHED
                )
                chain.doFilter(request, response)
            }
        ).also { registration ->
            registration.setName("establishedCorrelationRecorder")
            registration.order = Ordered.LOWEST_PRECEDENCE
            registration.addUrlPatterns("/*")
        }
    }
}

/**
 * The same Arc endpoints with `cratis.arc.correlation-enabled=false`, the documented way for an
 * application to own correlation itself.
 *
 * Nothing establishes an identifier for the request then, so every Arc endpoint has to keep
 * resolving one from the inbound header and generating one when the client sent none. Sharing the
 * resolver must not turn that fallback into a missing correlation identifier.
 */
@SpringBootTest(
    classes = [ArcEndpointCorrelationTests.Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["cratis.arc.correlation-enabled=false"]
)
internal class ArcEndpointCorrelationWithoutFilterTests {
    @Autowired
    lateinit var objectMapper: ObjectMapper

    @LocalServerPort
    var port: Int = 0

    private val probe by lazy { EndpointProbe(port) }

    @Test
    fun `every Arc endpoint still generates a correlation identifier when the client sends none`() {
        ARC_ENDPOINTS.forEach { endpoint ->
            val response = probe.send(endpoint)

            assertHandled(endpoint, response)
            assertEquals(
                NOTHING_ESTABLISHED,
                establishedCorrelation(response),
                "${endpoint.name}: the disabled correlation filter still ran."
            )
            val echoed = correlationHeader(response)
            UUID.fromString(echoed)
            if (endpoint.reportsCorrelationInBody) {
                assertEquals(
                    echoed,
                    objectMapper.readTree(response.body()).path("correlationId").stringValue(),
                    "${endpoint.name} reported a correlation identifier it did not echo."
                )
            }
        }
    }

    @Test
    fun `every Arc endpoint still keeps the correlation identifier the client supplied`() {
        ARC_ENDPOINTS.forEach { endpoint ->
            val supplied = UUID.randomUUID().toString()

            val response = probe.send(endpoint, supplied)

            assertHandled(endpoint, response)
            assertEquals(supplied, correlationHeader(response), "${endpoint.name} echoed a different correlation identifier.")
        }
    }

    @Test
    fun `separate requests to an Arc endpoint receive separate generated correlation identifiers`() {
        val first = correlationHeader(probe.send(ARC_ENDPOINTS.first()))
        val second = correlationHeader(probe.send(ARC_ENDPOINTS.first()))

        assertNotEquals(first, second)
    }
}

/** An Arc endpoint that resolves a correlation identifier for the request it handles. */
private class ArcEndpoint(
    val name: String,
    val method: String,
    val path: String,
    val body: String? = null,
    val expectedStatus: Int = 200,
    val reportsCorrelationInBody: Boolean = true
)

/** Sends requests to the Arc endpoints under test. */
private class EndpointProbe(private val port: Int) {
    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun send(endpoint: ArcEndpoint, correlationId: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port${endpoint.path}"))
            .timeout(Duration.ofSeconds(10))
        if (correlationId != null) request.header(CORRELATION_HEADER, correlationId)
        if (endpoint.body != null) request.header("Content-Type", "application/json")
        return http.send(
            request.method(
                endpoint.method,
                endpoint.body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
            ).build(),
            HttpResponse.BodyHandlers.ofString()
        )
    }
}

/** Proves the Arc endpoint itself answered, so a route that never ran cannot pass by default. */
private fun assertHandled(endpoint: ArcEndpoint, response: HttpResponse<String>) = assertEquals(
    endpoint.expectedStatus,
    response.statusCode(),
    "${endpoint.name} did not handle the request: ${response.body()}"
)

private fun correlationHeader(response: HttpResponse<String>): String = response.headers()
    .firstValue(CORRELATION_HEADER)
    .orElseThrow { AssertionError("The response carried no $CORRELATION_HEADER header.") }

private fun establishedCorrelation(response: HttpResponse<String>): String = response.headers()
    .firstValue(ESTABLISHED_CORRELATION_HEADER)
    .orElseThrow { AssertionError("The response carried no $ESTABLISHED_CORRELATION_HEADER header.") }

/**
 * One endpoint for each place that resolves a correlation identifier for an inbound Arc request:
 * the command handler, the one-shot query handler, the observable query health endpoint, and the
 * observable query transport. The unsubscribe route answers `404` for an unknown connection, which
 * is enough - the transport resolves correlation before it looks the connection up.
 */
private val ARC_ENDPOINTS = listOf(
    ArcEndpoint("The Arc command endpoint", "POST", ARC_FIXTURE_ROUTE, """{"value":"hello"}"""),
    ArcEndpoint("The Arc query endpoint", "GET", ARC_FIXTURE_ROUTE),
    ArcEndpoint("The observable query health endpoint", "GET", "/.cratis/queries/health"),
    ArcEndpoint(
        "The observable query transport",
        "POST",
        "/.cratis/queries/sse/unsubscribe",
        """{"connectionId":"unknown-connection","queryId":"unknown-query"}""",
        expectedStatus = 404,
        reportsCorrelationInBody = false
    )
)

private const val ARC_FIXTURE_ROUTE = "/api/fixtures/java-fixture-command"
private const val ESTABLISHED_CORRELATION_HEADER = "X-Test-Established-Correlation"
private const val NOTHING_ESTABLISHED = "none"
