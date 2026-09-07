// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authentication.AuthenticationHandler
import io.cratis.arc.authentication.AuthenticationResult
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.SecurityContextHolderFilter

/**
 * Ordering of the Arc correlation filter against the two filters that would otherwise observe a
 * request before correlation exists: Spring Security's chain and Arc authentication.
 */
@SpringBootTest(
    classes = [ArcCorrelationOrderingTests.Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
internal class ArcCorrelationOrderingTests {
    @LocalServerPort
    var port: Int = 0

    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    @BeforeEach
    fun resetObservations() {
        Application.securityChainCorrelation = null
        Application.authenticationCorrelation = null
    }

    @Test
    fun `the Spring Security filter chain observes the correlation identifier of the request`() {
        val response = send("GET", PLAIN_ROUTE)

        assertEquals(200, response.statusCode())
        assertEquals(correlationHeader(response), Application.securityChainCorrelation)
    }

    @Test
    fun `Arc authentication observes the correlation identifier established for the request`() {
        val response = send("POST", ARC_ROUTE, """{"value":"hello"}""")

        assertEquals(200, response.statusCode())
        val echoed = correlationHeader(response)
        UUID.fromString(echoed)
        assertEquals(echoed, Application.authenticationCorrelation)
    }

    private fun correlationHeader(response: HttpResponse<String>): String = response.headers()
        .firstValue(CORRELATION_HEADER)
        .orElseThrow { AssertionError("The response carried no $CORRELATION_HEADER header.") }

    private fun send(method: String, path: String, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(10))
        if (body != null) request.header("Content-Type", "application/json")
        return http.send(
            request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class Application {
        /** Registers the non-Arc controller the way an application would. */
        @Bean
        fun plainController(): PlainCorrelationController = PlainCorrelationController()

        /** Records the correlation Arc authentication observes for an Arc route. */
        @Bean
        fun authenticationHandler(): AuthenticationHandler = AuthenticationHandler { context ->
            authenticationCorrelation = context.header(CORRELATION_HEADER)
            AuthenticationResult.ANONYMOUS
        }

        /** Records the correlation the earliest filter inside Spring Security's chain observes. */
        @Bean
        fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
            .csrf { csrf -> csrf.disable() }
            .authorizeHttpRequests { requests -> requests.anyRequest().permitAll() }
            .addFilterBefore(ObservingFilter(), SecurityContextHolderFilter::class.java)
            .build()

        private class ObservingFilter : Filter {
            override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
                Application.securityChainCorrelation = ArcCorrelation.of(request as HttpServletRequest)?.toString()
                chain.doFilter(request, response)
            }
        }

        companion object {
            @Volatile
            var securityChainCorrelation: String? = null

            @Volatile
            var authenticationCorrelation: String? = null
        }
    }

    private companion object {
        const val ARC_ROUTE = "/api/fixtures/java-fixture-command"
    }
}
