// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.BlockingObservableQueryEmissionGuard
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.GuardObservableQueryEmission
import io.cratis.arc.queries.ObservableQueryEmissionVerdict
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryTransportType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** Real Tomcat completion/close must follow every accepted finite or terminal envelope. */
@SpringBootTest(
    classes = [ArcObservableQueryTerminalDrainHostingTests.Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["cratis.arc.observable-queries.keep-alive-interval=0", "cratis.arc.request-timeout=2s"]
)
internal class ArcObservableQueryTerminalDrainHostingTests {
    @LocalServerPort
    var port: Int = 0
    @Autowired
    lateinit var mapper: ObjectMapper
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @ParameterizedTest
    @ValueSource(strings = ["finite", "openingFailure", "terminalUnauthorized"])
    fun `Tomcat SSE completes with EOF only after all finite or terminal envelopes`(scenario: String) {
        val response = client.sendAsync(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/terminal-drain/$scenario"))
                .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofString()
        ).get(5, TimeUnit.SECONDS)
        assertEquals(200, response.statusCode())
        // BodyHandlers.ofString completes only at EOF, not merely after the first frame.
        val envelopes = response.body().lineSequence().filter { it.startsWith("data: ") }
            .map { mapper.readTree(it.removePrefix("data: ")) }.toList()
        assertEnvelopes(scenario, envelopes)
    }

    @ParameterizedTest
    @ValueSource(strings = ["finite", "openingFailure", "terminalUnauthorized"])
    fun `Tomcat WebSocket closes normally only after all finite or terminal envelopes`(scenario: String) {
        val listener = SocketListener()
        val socket = client.newWebSocketBuilder().buildAsync(
            URI.create("ws://127.0.0.1:$port/terminal-drain/$scenario"), listener
        ).get(5, TimeUnit.SECONDS)
        try {
            assertEquals(WebSocket.NORMAL_CLOSURE, listener.closed.get(5, TimeUnit.SECONDS))
            val envelopes = listener.frames.toList().map { text ->
                val frame = mapper.readTree(text)
                assertEquals("Data", frame.path("type").stringValue())
                frame.path("data")
            }
            assertEnvelopes(scenario, envelopes)
        } finally {
            socket.abort()
        }
    }

    private fun assertEnvelopes(scenario: String, envelopes: List<JsonNode>) {
        when (scenario) {
            "finite" -> assertEquals(listOf("one", "two", "three"), envelopes.map { it.path("data").stringValue() })
            "openingFailure" -> {
                assertEquals(1, envelopes.size)
                assertTrue(envelopes.single().path("hasExceptions").booleanValue())
            }
            "terminalUnauthorized" -> {
                assertEquals(2, envelopes.size)
                assertEquals("one", envelopes.first().path("data").stringValue())
                assertEquals(false, envelopes.last().path("isAuthorized").booleanValue())
            }
        }
    }

    private class SocketListener : WebSocket.Listener {
        val frames = LinkedBlockingQueue<String>()
        val closed = CompletableFuture<Int>()
        private val partial = StringBuilder()
        override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            partial.append(data)
            if (last) {
                frames.add(partial.toString())
                partial.setLength(0)
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }
        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            closed.complete(statusCode)
            return CompletableFuture.completedFuture(null)
        }
        override fun onError(webSocket: WebSocket, error: Throwable) { closed.completeExceptionally(error) }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
    class Application {
        @Bean
        fun terminalModule(): ArcArtifactModule = object : ArcArtifactModule(emptyList(), listOf(
            TerminalPerformer("finite"), TerminalPerformer("openingFailure"), TerminalPerformer("terminalUnauthorized")
        )) {}
        @Bean
        fun terminalGuard(): GuardObservableQueryEmission = BlockingObservableQueryEmissionGuard { context ->
            if (context.data == "denied") ObservableQueryEmissionVerdict.DENY_AND_TERMINATE else ObservableQueryEmissionVerdict.ALLOW
        }
    }

    private class TerminalPerformer(private val scenario: String) : QueryPerformer {
        override val fullyQualifiedName = FullyQualifiedQueryName("terminal.$scenario")
        override val descriptor = QueryDescriptor(
            scenario, "terminal", "kotlin.String", fullyQualifiedName = fullyQualifiedName.value,
            authorization = AuthorizationMetadata(allowAnonymous = true),
            explicitPath = "/terminal-drain/$scenario", transport = QueryTransportType.OBSERVABLE
        )
        override suspend fun perform(context: QueryContext): Any = when (scenario) {
            "openingFailure" -> error("Opening failed")
            "terminalUnauthorized" -> flowOf("one", "denied", "must never be emitted")
            else -> flowOf("one", "two", "three")
        }
    }
}
