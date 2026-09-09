// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * Host-wide correlation propagation. `/plain/correlation` is an ordinary Spring MVC route that Arc
 * does not host, registered next to the Arc command and query fixtures.
 */
@SpringBootTest(
    classes = [ArcCorrelationHostingTests.Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
internal class ArcCorrelationHostingTests {
    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    @Qualifier("arcCorrelationFilterRegistration")
    lateinit var correlationRegistration: FilterRegistrationBean<*>

    @Autowired
    @Qualifier("arcAuthenticationFilterRegistration")
    lateinit var authenticationRegistration: FilterRegistrationBean<*>

    @LocalServerPort
    var port: Int = 0

    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    @Test
    fun `plain Spring route sees a generated correlation identifier when the client sends none`() {
        val response = send("GET", PLAIN_ROUTE)

        assertEquals(200, response.statusCode())
        val echoed = correlationHeader(response)
        UUID.fromString(echoed)
        val observed = objectMapper.readTree(response.body())
        assertEquals(echoed, observed.path("attribute").textValue())
        assertEquals(echoed, observed.path("header").textValue())
        assertEquals(echoed, observed.path("logging").textValue())
    }

    @Test
    fun `plain Spring route reuses a correlation identifier supplied by the client`() {
        val supplied = UUID.randomUUID().toString()

        val response = send("GET", PLAIN_ROUTE, supplied)

        assertEquals(supplied, correlationHeader(response))
        val observed = objectMapper.readTree(response.body())
        assertEquals(supplied, observed.path("attribute").textValue())
        assertEquals(supplied, observed.path("header").textValue())
        assertEquals(supplied, observed.path("logging").textValue())
    }

    @Test
    fun `correlation text that is not an identifier never reaches a plain Spring route`() {
        val response = send("GET", PLAIN_ROUTE, "not-a-correlation-id")

        val echoed = correlationHeader(response)
        assertNotEquals("not-a-correlation-id", echoed)
        UUID.fromString(echoed)
        val observed = objectMapper.readTree(response.body())
        assertEquals(echoed, observed.path("attribute").textValue())
        assertEquals(echoed, observed.path("header").textValue())
    }

    @Test
    fun `separate plain Spring requests receive separate correlation identifiers`() {
        val first = correlationHeader(send("GET", PLAIN_ROUTE))
        val second = correlationHeader(send("GET", PLAIN_ROUTE))

        assertNotEquals(first, second)
    }

    @Test
    fun `Arc command route keeps reporting the correlation identifier of the request`() {
        val supplied = UUID.randomUUID().toString()

        val correlated = send("POST", ARC_ROUTE, supplied, """{"value":"hello"}""")
        assertEquals(200, correlated.statusCode())
        assertEquals(supplied, correlationHeader(correlated))
        assertEquals(supplied, objectMapper.readTree(correlated.body()).path("correlationId").textValue())

        val generated = send("POST", ARC_ROUTE, body = """{"value":"hello"}""")
        assertEquals(200, generated.statusCode())
        val echoed = correlationHeader(generated)
        UUID.fromString(echoed)
        assertEquals(echoed, objectMapper.readTree(generated.body()).path("correlationId").textValue())
    }

    @Test
    fun `Arc query route keeps reporting the correlation identifier of the request`() {
        val supplied = UUID.randomUUID().toString()

        val correlated = send("GET", ARC_ROUTE, supplied)
        assertEquals(200, correlated.statusCode())
        assertEquals(supplied, correlationHeader(correlated))
        assertEquals(supplied, objectMapper.readTree(correlated.body()).path("correlationId").textValue())

        val generated = send("GET", ARC_ROUTE)
        val echoed = correlationHeader(generated)
        assertEquals(echoed, objectMapper.readTree(generated.body()).path("correlationId").textValue())
    }

    @Test
    fun `correlation filter runs before Spring Security and Arc authentication`() {
        assertTrue(
            correlationRegistration.order < SecurityFilterProperties.DEFAULT_FILTER_ORDER,
            "Arc correlation must precede springSecurityFilterChain."
        )
        assertTrue(
            SecurityFilterProperties.DEFAULT_FILTER_ORDER < authenticationRegistration.order,
            "Arc authentication is expected to keep running after Spring Security."
        )
    }

    @Test
    fun `correlation filter backs off for configuration and for an application supplied registration`() {
        val runner = WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcCorrelationAutoConfiguration::class.java))

        runner.run { context -> assertTrue(context.containsBean("arcCorrelationFilterRegistration")) }
        runner.withPropertyValues("cratis.arc.correlation-enabled=false").run { context ->
            assertFalse(context.containsBean("arcCorrelationFilterRegistration"))
        }
        runner.withUserConfiguration(ReplacedCorrelationFilter::class.java).run { context ->
            assertEquals(1, context.getBeanNamesForType(FilterRegistrationBean::class.java).size)
            val replaced = context.getBean("arcCorrelationFilterRegistration", FilterRegistrationBean::class.java)
            assertFalse(replaced.filter is ArcCorrelationFilter)
        }
    }

    private fun correlationHeader(response: HttpResponse<String>): String = response.headers()
        .firstValue(CORRELATION_HEADER)
        .orElseThrow { AssertionError("The response carried no $CORRELATION_HEADER header.") }

    private fun send(
        method: String,
        path: String,
        correlationId: String? = null,
        body: String? = null
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(5))
        if (correlationId != null) request.header(CORRELATION_HEADER, correlationId)
        if (body != null) request.header("Content-Type", "application/json")
        return http.send(
            request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
    class Application {
        /** Registers the non-Arc controller the way an application would. */
        @Bean
        fun plainController(): PlainCorrelationController = PlainCorrelationController()
    }

    private companion object {
        const val ARC_ROUTE = "/api/fixtures/java-fixture-command"
    }
}

/** Ordinary Spring MVC controller, unknown to Arc, reporting where correlation is visible. */
@RestController
class PlainCorrelationController {
    @GetMapping(PLAIN_ROUTE)
    fun correlation(
        request: HttpServletRequest,
        @RequestHeader(name = CORRELATION_HEADER, required = false) header: String?
    ): Map<String, String?> = mapOf(
        "attribute" to ArcCorrelation.of(request)?.toString(),
        "header" to header,
        "logging" to MDC.get(ArcCorrelation.LOGGING_KEY)
    )
}

/** Proves the Arc registration backs off for an application-owned bean of the same name. */
internal class ReplacedCorrelationFilter {
    @Bean("arcCorrelationFilterRegistration")
    fun arcCorrelationFilterRegistration(): FilterRegistrationBean<Filter> = FilterRegistrationBean(
        Filter { request, response, chain -> chain.doFilter(request, response) }
    )
}

internal const val CORRELATION_HEADER: String = "X-Correlation-ID"
internal const val PLAIN_ROUTE: String = "/plain/correlation"
