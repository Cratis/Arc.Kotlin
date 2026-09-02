// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authentication.AuthenticationHandler
import io.cratis.arc.authentication.AuthenticationResult
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.RouteOptions
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.queries.asKotlinFlow
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow as JdkFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean

@SpringBootTest(
    classes = [ArcObservableQueryHostingTests.Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "cratis.arc.observable-queries.keep-alive-interval=200ms",
        "cratis.arc.observable-queries.connection-timeout=10s",
        "cratis.arc.observable-queries.maximum-connections=16",
        "cratis.arc.correlation-header=X-Arc-Correlation",
        "cratis.arc.tenancy.resolvers=development",
        "cratis.arc.tenancy.fixed-tenant-id=observable-tenant"
    ]
)
internal class ArcObservableQueryHostingTests {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var transport: ArcObservableQueryTransport

    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val sockets = mutableListOf<WebSocket>()
    private val streams = mutableListOf<HttpResponse<java.io.InputStream>>()

    @BeforeEach
    fun resetFlow() {
        ObservableFixtureModule.items.value = listOf(Item(1, "one"))
        ObservableFixtureModule.context.set(null)
    }

    @AfterEach
    fun cleanup() {
        sockets.forEach { socket -> runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS) } }
        streams.forEach { response -> runCatching { response.body().close() } }
        sockets.clear()
        streams.clear()
    }

    @Test
    fun `observable QUERY snapshots support Kotlin Flow and JDK Publisher with no-store and correlation echo`() {
        val correlationId = "91625a7c-b7ee-48a2-a055-edf23532f109"
        listOf(OBSERVABLE_ROUTE, PUBLISHER_ROUTE).forEach { route ->
            val response = http.send(
                HttpRequest.newBuilder(httpUri("$route?waitForFirstResult=true&waitForFirstResultTimeout=2"))
                    .header("Content-Type", "application/json")
                    .header("x-arc-correlation", correlationId)
                    .method("QUERY", HttpRequest.BodyPublishers.ofString("""{"arguments":{},"paging":{},"sorting":{}}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, response.statusCode(), "$route: ${response.body()}")
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""))
            assertEquals(correlationId, response.headers().firstValue("X-Arc-Correlation").orElse(""))
            val envelope = objectMapper.readTree(response.body())
            assertEquals(correlationId, envelope.path("correlationId").textValue())
            assertEquals("one", envelope.path("data").path(0).path("value").textValue())
        }
    }

    @Test
    fun `observable HTTP snapshot supports accepted and bounded first result wait`() {
        val pending = http.send(
            HttpRequest.newBuilder(httpUri(OBSERVABLE_ROUTE)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(202, pending.statusCode())
        assertFalse(objectMapper.readTree(pending.body()).path("isReady").booleanValue())

        val correlationId = "168e3990-d5c9-4c64-a725-8d672efa28b3"
        val ready = http.send(
            HttpRequest.newBuilder(httpUri("$OBSERVABLE_ROUTE?waitForFirstResult=true&waitForFirstResultTimeout=2"))
                .header("X-Arc-Correlation", correlationId)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(200, ready.statusCode())
        assertEquals(correlationId, ready.headers().firstValue("X-Arc-Correlation").orElse(""))
        val readyEnvelope = objectMapper.readTree(ready.body())
        assertEquals(correlationId, readyEnvelope.path("correlationId").textValue())
        assertEquals("one", readyEnvelope.path("data").path(0).path("value").textValue())
        assertEquals("observable-tenant", requireNotNull(ObservableFixtureModule.context.get()).tenantId)
        assertEquals("observable-tenant", requireNotNull(ObservableFixtureModule.context.get()).tenantNamespace)
    }

    @Test
    fun `observable GET ignores infrastructure parameters while subscriptions reject them`() {
        val direct = http.send(
            HttpRequest.newBuilder(
                httpUri(
                    "$OBSERVABLE_ROUTE?waitForFirstResult=true&waitForFirstResultTimeout=2&" +
                        "dependency=forged&request=forged&context=forged"
                )
            ).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(200, direct.statusCode())
        assertTrue(requireNotNull(ObservableFixtureModule.context.get()).request.arguments.isEmpty())

        val socket = openSocket(OBSERVABLE_QUERY_WS_ROUTE)
        socket.awaitType("Connected")
        socket.send(
            """{"type":"Subscribe","queryId":"q-infrastructure","revision":1,"payload":{"queryName":"$OBSERVABLE_NAME","transferMode":"full","arguments":{"dependency":"forged","request":"forged","context":"forged"}}}"""
        )
        val rejected = socket.awaitType("Error")
        assertEquals("q-infrastructure", rejected.path("queryId").textValue())
    }

    @Test
    fun `observable binders preserve omission supplied values malformed values and explicit null`() {
        val absent = http.send(
            HttpRequest.newBuilder(
                httpUri("$DEFAULTED_ROUTE?waitForFirstResult=true&waitForFirstResultTimeout=2")
            ).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(200, absent.statusCode())
        assertEquals("default-7", objectMapper.readTree(absent.body()).path("data").path(0).path("value").textValue())

        val supplied = http.send(
            HttpRequest.newBuilder(
                httpUri("$DEFAULTED_ROUTE?count=9&waitForFirstResult=true&waitForFirstResultTimeout=2")
            ).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(200, supplied.statusCode())
        assertEquals("supplied-9", objectMapper.readTree(supplied.body()).path("data").path(0).path("value").textValue())

        val malformed = http.send(
            HttpRequest.newBuilder(
                httpUri("$DEFAULTED_ROUTE?count=not-a-number&waitForFirstResult=true&waitForFirstResultTimeout=2")
            ).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(400, malformed.statusCode())
        assertEquals(
            "malformedRequest",
            objectMapper.readTree(malformed.body()).path("validationResults").path(0).path("reason").textValue()
        )

        val socket = openSocket(OBSERVABLE_QUERY_WS_ROUTE)
        socket.awaitType("Connected")
        socket.send(subscribe("q-default", 1, DEFAULTED_NAME, "full"))
        assertEquals("default-7", socket.awaitQuery("q-default").path("payload").path("data").path(0).path("value").textValue())
        socket.send(
            """{"type":"Subscribe","queryId":"q-null","revision":2,"payload":{"queryName":"$DEFAULTED_NAME","transferMode":"full","arguments":{"count":null}}}"""
        )
        assertEquals("supplied-null", socket.awaitQuery("q-null").path("payload").path("data").path(0).path("value").textValue())
    }

    @Test
    fun `direct SSE and WebSocket preserve their caller supplied correlation IDs`() {
        val sseCorrelation = "3ea2f3d6-bb3a-4ca5-b5e4-f178e40dd04d"
        val sse = openSse(OBSERVABLE_ROUTE, sseCorrelation)
        assertEquals(sseCorrelation, sse.headers().firstValue("X-Arc-Correlation").orElse(""))
        val line = BufferedReader(sse.body().reader()).readLine()
        assertTrue(line.startsWith("data: {"))
        val sseEnvelope = objectMapper.readTree(line.removePrefix("data: "))
        assertEquals(sseCorrelation, sseEnvelope.path("correlationId").textValue())
        assertEquals("one", sseEnvelope.path("data").path(0).path("value").textValue())

        val webSocketCorrelation = "c77e8e51-5aca-4884-bc4e-b20533185a97"
        val socket = openSocket(OBSERVABLE_ROUTE, correlationId = webSocketCorrelation)
        val data = socket.awaitJson()
        assertEquals("Data", data.path("type").textValue())
        assertEquals(webSocketCorrelation, data.path("data").path("correlationId").textValue())
        assertEquals("one", data.path("data").path("data").path(0).path("value").textValue())
        socket.socket.sendText("""{"type":"Ping","timestamp":42}""", true).get(2, TimeUnit.SECONDS)
        val pong = socket.awaitType("Pong")
        assertEquals(42, pong.path("timestamp").longValue())
    }

    @Test
    fun `WebSocket hub supports handshake revisions correlation ping full delta and unauthorized terminal`() {
        val correlationId = "d637095e-004f-41f8-bd87-93dd54b11812"
        val socket = openSocket(OBSERVABLE_QUERY_WS_ROUTE, correlationId = correlationId)
        val connected = socket.awaitType("Connected")
        assertTrue(connected.path("supportsSubscriptionRevisions").booleanValue())
        assertEquals(200, connected.path("keepAliveIntervalMs").longValue())

        socket.send(subscribe("q-full", 1, OBSERVABLE_NAME, "full"))
        val first = socket.awaitQuery("q-full")
        assertEquals(1, first.path("revision").longValue())
        assertEquals(correlationId, first.path("payload").path("correlationId").textValue())
        assertEquals("one", first.path("payload").path("data").path(0).path("value").textValue())

        socket.send(subscribe("q-delta", 2, OBSERVABLE_NAME, "delta"))
        socket.awaitQuery("q-delta")
        ObservableFixtureModule.items.value = listOf(Item(1, "two"), Item(2, "added"))
        val delta = socket.awaitQuery("q-delta")
        assertEquals(2, delta.path("revision").longValue())
        val deltaData = delta.path("payload").path("data")
        assertTrue(deltaData.isNull || deltaData.isMissingNode)
        val changeSet = delta.path("payload").path("changeSet")
        assertTrue(changeSet.isObject, delta.toString())

        socket.send("""{"type":"Ping","timestamp":1234}""")
        assertEquals(1234, socket.awaitType("Pong").path("timestamp").longValue())

        socket.send(subscribe("q-secured", 3, SECURED_NAME, "full"))
        val unauthorized = socket.awaitType("Unauthorized")
        assertEquals("q-secured", unauthorized.path("queryId").textValue())
        assertEquals(3, unauthorized.path("revision").longValue())
        ObservableFixtureModule.items.value = listOf(Item(1, "still-isolated"))
        assertEquals(
            "still-isolated",
            socket.awaitQuery("q-full").path("payload").path("data").path(0).path("value").textValue()
        )
    }

    @Test
    fun `multiplexed transport captures optional credentials and authorizes each subscription`() {
        val anonymous = openSocket(OBSERVABLE_QUERY_WS_ROUTE)
        anonymous.awaitType("Connected")
        anonymous.send(subscribe("anonymous", 1, OBSERVABLE_NAME, "full"))
        assertEquals("one", anonymous.awaitQuery("anonymous").path("payload").path("data").path(0).path("value").textValue())
        anonymous.send(subscribe("protected", 2, SECURED_NAME, "full"))
        assertEquals("protected", anonymous.awaitType("Unauthorized").path("queryId").textValue())

        val authenticated = openSocket(OBSERVABLE_QUERY_WS_ROUTE, "Bearer good")
        authenticated.awaitType("Connected")
        authenticated.send(subscribe("protected-authenticated", 3, SECURED_NAME, "full"))
        assertEquals(
            "one",
            authenticated.awaitQuery("protected-authenticated").path("payload").path("data").path(0).path("value").textValue()
        )
    }

    @Test
    fun `hub subscription arguments are case insensitive and reject ambiguous duplicates and unknown fields`() {
        val socket = openSocket(OBSERVABLE_QUERY_WS_ROUTE)
        socket.awaitType("Connected")
        socket.send(
            """{"type":"Subscribe","queryId":"q-mixed","revision":1,"payload":{"queryName":"$DEFAULTED_NAME","arguments":{"CoUnT":"9"}}}"""
        )
        assertEquals(
            "supplied-9",
            socket.awaitQuery("q-mixed").path("payload").path("data").path(0).path("value").textValue()
        )

        socket.send(
            """{"type":"Subscribe","queryId":"q-duplicate","revision":2,"payload":{"queryName":"$DEFAULTED_NAME","arguments":{"count":null,"COUNT":"9"}}}"""
        )
        assertEquals("q-duplicate", socket.awaitType("Error").path("queryId").textValue())

        socket.send(
            """{"type":"Subscribe","queryId":"q-unknown","revision":3,"payload":{"queryName":"$DEFAULTED_NAME","arguments":{"unknown":"9"}}}"""
        )
        assertEquals("q-unknown", socket.awaitType("Error").path("queryId").textValue())
    }

    @Test
    fun `connection close cancels and releases observable transport capacity`() {
        val baseline = transport.activeConnectionCount
        val socket = openSocket(OBSERVABLE_QUERY_WS_ROUTE)
        socket.awaitType("Connected")
        val sse = openSse(OBSERVABLE_QUERY_SSE_ROUTE)
        BufferedReader(sse.body().reader()).use { reader -> readSseMessage(reader) }
        socket.socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS)
        sse.body().close()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (transport.activeConnectionCount != baseline && System.nanoTime() < deadline) {
            Thread.sleep(25)
        }
        assertTrue(transport.activeConnectionCount <= baseline)
        sockets.clear()
        streams.clear()
    }

    @Test
    fun `SSE hub preserves subscribe correlation and supports unknown connection and unsubscribe`() {
        val sse = openSse(OBSERVABLE_QUERY_SSE_ROUTE)
        val reader = BufferedReader(sse.body().reader())
        val connected = readSseMessage(reader)
        assertEquals("Connected", connected.path("type").textValue())
        val connectionId = connected.path("payload").textValue()
        assertTrue(connectionId.isNotBlank())

        val unknown = postJson(
            OBSERVABLE_QUERY_SSE_SUBSCRIBE_ROUTE,
            subscribeBody("missing", "q", 1, OBSERVABLE_NAME)
        )
        assertEquals(404, unknown.statusCode())

        val correlationId = "473e63c0-a4e9-4bf5-af65-86368482e0a8"
        val accepted = postJson(
            OBSERVABLE_QUERY_SSE_SUBSCRIBE_ROUTE,
            subscribeBody(connectionId, "q-sse", 4, OBSERVABLE_NAME),
            correlationId
        )
        assertEquals(200, accepted.statusCode())
        assertEquals(correlationId, accepted.headers().firstValue("X-Arc-Correlation").orElse(""))
        val result = generateSequence { readSseMessage(reader) }.first { it.path("type").textValue() == "QueryResult" }
        assertEquals("q-sse", result.path("queryId").textValue())
        assertEquals(4, result.path("revision").longValue())
        assertEquals(correlationId, result.path("payload").path("correlationId").textValue())

        val removed = postJson(
            OBSERVABLE_QUERY_SSE_UNSUBSCRIBE_ROUTE,
            """{"connectionId":"$connectionId","queryId":"q-sse","revision":4}"""
        )
        assertEquals(200, removed.statusCode())
    }

    private fun openSocket(
        path: String,
        authorization: String? = null,
        correlationId: String? = null
    ): TestSocket {
        val listener = TestSocket(objectMapper)
        val builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
        if (authorization != null) builder.header("Authorization", authorization)
        if (correlationId != null) builder.header("X-Arc-Correlation", correlationId)
        listener.socket = builder
            .buildAsync(wsUri(path), listener)
            .get(5, TimeUnit.SECONDS)
        sockets.add(listener.socket)
        return listener
    }

    private fun openSse(path: String, correlationId: String? = null): HttpResponse<java.io.InputStream> {
        val request = HttpRequest.newBuilder(httpUri(path)).header("Accept", "text/event-stream")
        if (correlationId != null) request.header("X-Arc-Correlation", correlationId)
        val response = http.sendAsync(
            request.GET().build(),
            HttpResponse.BodyHandlers.ofInputStream()
        ).get(5, TimeUnit.SECONDS)
        assertEquals(200, response.statusCode())
        assertEquals("text/event-stream;charset=UTF-8", response.headers().firstValue("Content-Type").orElse(""))
        streams.add(response)
        return response
    }

    private fun postJson(path: String, json: String, correlationId: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(httpUri(path))
            .header("Content-Type", "application/json")
        if (correlationId != null) request.header("X-Arc-Correlation", correlationId)
        return http.send(
            request.POST(HttpRequest.BodyPublishers.ofString(json)).build(),
            HttpResponse.BodyHandlers.ofString()
        )
    }

    private fun subscribe(queryId: String, revision: Long, queryName: String, transferMode: String): String =
        """{"type":"Subscribe","queryId":"$queryId","revision":$revision,"payload":{"queryName":"$queryName","transferMode":"$transferMode"}}"""

    private fun subscribeBody(connectionId: String, queryId: String, revision: Long, queryName: String): String =
        """{"connectionId":"$connectionId","queryId":"$queryId","revision":$revision,"request":{"queryName":"$queryName","transferMode":"full"}}"""

    private fun readSseMessage(reader: BufferedReader): JsonNode {
        while (true) {
            val line = reader.readLine()
            assertNotNull(line)
            if (line.startsWith("data: ")) return objectMapper.readTree(line.removePrefix("data: "))
        }
    }

    private fun httpUri(path: String) = URI.create("http://127.0.0.1:$port$path")
    private fun wsUri(path: String) = URI.create("ws://127.0.0.1:$port$path")

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class])
    class Application {
        @Bean
        fun observableFixtureModule(): ArcArtifactModule = ObservableFixtureModule()

        @Bean
        fun authenticationHandler(): AuthenticationHandler = AuthenticationHandler { context ->
            if (context.header("Authorization") == "Bearer good") {
                AuthenticationResult.succeeded(ArcPrincipal("alice", true, setOf("admin"), "alice"))
            } else {
                AuthenticationResult.ANONYMOUS
            }
        }
    }

    private companion object {
        const val DEFAULTED_NAME = "io.cratis.arc.springboot.ObservableFixture.defaulted"
        const val DEFAULTED_ROUTE = "/api/fixtures/observable-defaulted"
        const val OBSERVABLE_ROUTE = "/api/fixtures/observable-items"
        const val OBSERVABLE_NAME = "io.cratis.arc.springboot.ObservableFixture.observe"
        const val PUBLISHER_ROUTE = "/api/fixtures/observable-publisher"
        const val SECURED_NAME = "io.cratis.arc.springboot.ObservableFixture.secured"
    }
}

internal data class Item(val id: Int, val value: String)

private class ObservableFixtureModule : ArcArtifactModule(
    emptyList(),
    listOf(
        ObservablePerformer("observe", "/api/fixtures/observable-items", true),
        ObservablePerformer("publisher", "/api/fixtures/observable-publisher", true, jdkPublisher = true),
        ObservablePerformer("secured", "/api/fixtures/observable-secured", false),
        ObservablePerformer("defaulted", "/api/fixtures/observable-defaulted", true, hasDefault = true)
    )
) {
    companion object {
        val items = MutableStateFlow(listOf(Item(1, "one")))
        val context = AtomicReference<QueryContext?>()
    }
}

private class ObservablePerformer(
    name: String,
    path: String,
    allowAnonymous: Boolean,
    private val hasDefault: Boolean = false,
    private val jdkPublisher: Boolean = false
) : QueryPerformer {
    override val fullyQualifiedName = FullyQualifiedQueryName("io.cratis.arc.springboot.ObservableFixture.$name")
    override val descriptor = QueryDescriptor(
        name,
        "io.cratis.arc.springboot.ObservableFixture",
        "kotlin.collections.List<io.cratis.arc.springboot.Item>",
        parameters = buildList {
            if (hasDefault) {
                add(
                    ParameterDescriptor(
                        "count",
                        TypeShapeDescriptor.value("kotlin.Int", nullable = true),
                        QueryParameterSource.CLIENT,
                        hasDefault = true
                    )
                )
            }
            add(
                ParameterDescriptor(
                    "dependency",
                    TypeShapeDescriptor.value("io.cratis.arc.springboot.ObservableDependency"),
                    QueryParameterSource.SERVICE
                )
            )
            add(
                ParameterDescriptor(
                    "request",
                    TypeShapeDescriptor.value(QueryRequest::class.java.name),
                    QueryParameterSource.QUERY_REQUEST
                )
            )
            add(
                ParameterDescriptor(
                    "context",
                    TypeShapeDescriptor.value(QueryContext::class.java.name),
                    QueryParameterSource.QUERY_CONTEXT
                )
            )
        },
        routeOptions = RouteOptions(path),
        fullyQualifiedName = fullyQualifiedName.value,
        location = listOf("fixtures"),
        authorization = AuthorizationMetadata(allowAnonymous, null, if (allowAnonymous) emptyList() else listOf("admin"), emptyList()),
        explicitPath = path,
        queryHttpMethod = if (jdkPublisher) QueryHttpMethodType.QUERY else QueryHttpMethodType.GET,
        transport = QueryTransportType.OBSERVABLE,
        isEnumerable = true
    )

    override suspend fun perform(context: QueryContext): Any {
        ObservableFixtureModule.context.set(context)
        if (hasDefault) {
            val supplied = context.request.arguments.containsKey("count")
            val value = if (supplied) context.request.arguments["count"] else 7
            return flowOf(listOf(Item(1, "${if (supplied) "supplied" else "default"}-$value")))
        }
        if (jdkPublisher) {
            val snapshot = ObservableFixtureModule.items.value
            return JdkFlow.Publisher<List<Item>> { subscriber ->
                subscriber.onSubscribe(object : JdkFlow.Subscription {
                    private var emitted = false

                    override fun request(count: Long) {
                        if (!emitted && count > 0) {
                            emitted = true
                            subscriber.onNext(snapshot)
                            subscriber.onComplete()
                        }
                    }

                    override fun cancel() {
                        emitted = true
                    }
                })
            }.asKotlinFlow()
        }
        return ObservableFixtureModule.items
    }
}

private class TestSocket(private val objectMapper: ObjectMapper) : WebSocket.Listener {
    val messages = LinkedBlockingQueue<JsonNode>()
    lateinit var socket: WebSocket
    private val text = StringBuilder()

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
        text.append(data)
        if (last) {
            messages.offer(objectMapper.readTree(text.toString()))
            text.setLength(0)
        }
        webSocket.request(1)
        return CompletableFuture.completedFuture(null)
    }

    fun send(value: String) {
        socket.sendText(value, true).get(2, TimeUnit.SECONDS)
    }

    fun awaitJson(): JsonNode = messages.poll(5, TimeUnit.SECONDS)
        ?: throw AssertionError("Timed out waiting for WebSocket message.")

    fun awaitType(type: String): JsonNode = generateSequence(::awaitJson).first { it.path("type").textValue() == type }

    fun awaitQuery(queryId: String): JsonNode = generateSequence(::awaitJson).first {
        it.path("type").textValue() == "QueryResult" && it.path("queryId").textValue() == queryId
    }
}
