// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts

import com.fasterxml.jackson.annotation.JsonInclude
import io.cratis.arc.queries.ObservableQueryHubMessage
import io.cratis.arc.queries.ObservableQueryHubMessageType
import io.cratis.arc.queries.ObservableQuerySSESubscribeRequest
import io.cratis.arc.queries.ObservableQuerySSEUnsubscribeRequest
import io.cratis.arc.queries.ObservableQuerySubscriptionRequest
import io.cratis.arc.queries.ObservableQueryTransferMode
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(
    classes = [GeneratedObservableQueryHostingTest.Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["cratis.arc.observable-queries.keep-alive-interval=100ms", "cratis.arc.observable-queries.connection-timeout=15s"]
)
internal class GeneratedObservableQueryHostingTest {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var mapper: ObjectMapper
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Test
    fun `generated Kotlin and Java typed performers execute through websocket hub with omission null and supplied defaults`() {
        val listener = SocketMessages()
        val correlation = UUID.randomUUID().toString()
        val socket = http.newWebSocketBuilder().header("X-Correlation-ID", correlation)
            .buildAsync(URI("ws://127.0.0.1:$port/.cratis/queries/ws"), listener).get(5, TimeUnit.SECONDS)
        try {
            assertEquals("Connected", listener.next().path("type").stringValue())
            cases().forEachIndexed { index, (request, expected) ->
                val queryId = "typed-$index"
                socket.sendText(encode(ObservableQueryHubMessage(
                    ObservableQueryHubMessageType.Subscribe, queryId, 8, request
                )), true).get(5, TimeUnit.SECONDS)
                assertResult(listener.nextResult(), queryId, expected)
                socket.sendText(encode(ObservableQueryHubMessage(
                    ObservableQueryHubMessageType.Unsubscribe, queryId, 8
                )), true).get(5, TimeUnit.SECONDS)
            }
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `generated Kotlin and Java typed performers execute through SSE hub with omission null and supplied defaults`() {
        val response = http.sendAsync(
            HttpRequest.newBuilder(uri("/.cratis/queries/sse")).header("Accept", "text/event-stream").GET().build(),
            HttpResponse.BodyHandlers.ofInputStream()
        ).get(5, TimeUnit.SECONDS)
        assertEquals(200, response.statusCode())
        response.body().use { stream ->
            val reader = BufferedReader(stream.reader())
            val connection = readSse(reader).path("payload").stringValue()
            cases().forEachIndexed { index, (request, expected) ->
                val queryId = "typed-$index"
                try {
                    assertEquals(200, post("subscribe", ObservableQuerySSESubscribeRequest(connection, queryId, request, 8)))
                    val message = generateSequence { readSse(reader) }.take(60)
                        .first { it.path("type").stringValue() != "Ping" }
                    assertResult(message, queryId, expected)
                } finally {
                    // Explicit unsubscribe, not socket EOF, owns cleanup until Q2 is addressed.
                    assertEquals(200, post("unsubscribe", ObservableQuerySSEUnsubscribeRequest(connection, queryId, 8)))
                }
            }
        }
    }

    private fun assertResult(message: JsonNode, queryId: String, expected: String) {
        assertEquals("QueryResult", message.path("type").stringValue(), message.toString())
        assertEquals(queryId, message.path("queryId").stringValue())
        assertEquals(8, message.path("revision").intValue())
        assertEquals(expected, message.path("payload").path("data").path("value").stringValue(), message.toString())
    }

    private fun cases(): List<Pair<ObservableQuerySubscriptionRequest, String>> {
        val id = "05bac18d-3e07-4c42-9cb8-85cc341da007"
        val arguments = linkedMapOf<String, String?>(
            "id" to id, "date" to "2026-06-11", "concept" to id, "ordinary" to "Active", "coded" to "17",
            "small" to "1", "ids" to id, "longs" to "1"
        )
        fun request(name: String, values: Map<String, String?>) = ObservableQuerySubscriptionRequest(
            "io.cratis.arc.contracts.fixtures.$name", values, 2, 7, "value", "desc", ObservableQueryTransferMode.FULL
        )
        return listOf(
            request("KotlinObservableSnapshot.observeKotlinSnapshot", arguments) to "2026-06-12|2|omitted|9|2|value",
            request("KotlinObservableSnapshot.observeKotlinSnapshot", arguments + ("optional" to null)) to "2026-06-12|2|supplied|null|2|value",
            request("KotlinObservableSnapshot.observeKotlinSnapshot", arguments + ("optional" to "3")) to "2026-06-12|2|supplied|3|2|value",
            request("JavaObservableSnapshot.observeJavaSnapshot", arguments) to "2026-06-12|2|java|2|value"
        )
    }

    private fun post(action: String, body: Any): Int = http.send(
        HttpRequest.newBuilder(uri("/.cratis/queries/sse/$action")).timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(encode(body))).build(),
        HttpResponse.BodyHandlers.ofString()
    ).statusCode()

    // A client must actually send explicit null; the server's response inclusion policy
    // intentionally omits null map values and is not the client wire encoder.
    private fun encode(body: Any): String = (mapper as JsonMapper).rebuild()
        .changeDefaultPropertyInclusion { it.withContentInclusion(JsonInclude.Include.ALWAYS) }
        .build().writeValueAsString(body)

    private fun readSse(reader: BufferedReader): JsonNode {
        while (true) {
            val line = reader.readLine()
            assertNotNull(line, "SSE stream ended before its result")
            if (line.startsWith("data: ")) return mapper.readTree(line.removePrefix("data: "))
        }
    }

    private fun uri(path: String) = URI("http://127.0.0.1:$port$path")

    private inner class SocketMessages : WebSocket.Listener {
        private val messages = LinkedBlockingQueue<JsonNode>()
        private val text = StringBuilder()
        override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            text.append(data)
            if (last) { messages.add(mapper.readTree(text.toString())); text.setLength(0) }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }
        fun next(): JsonNode = requireNotNull(messages.poll(5, TimeUnit.SECONDS)) { "No websocket frame" }
        fun nextResult(): JsonNode = generateSequence(::next).take(60).first { it.path("type").stringValue() != "Ping" }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class Application
}
