// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authentication.AuthenticationHandler
import io.cratis.arc.authentication.AuthenticationResult
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.RouteOptions
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.queries.BlockingObservableQueryEmissionGuard
import io.cratis.arc.queries.BlockingQueryRendererFor
import io.cratis.arc.queries.BlockingReadModelInterceptor
import io.cratis.arc.queries.DefaultObservableQueryPipeline
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.ObservableQueryEmissionVerdict
import io.cratis.arc.queries.ObservableQueryPipeline
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryRendererResult
import io.cratis.arc.queries.QueryTransportType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.test.annotation.DirtiesContext

@SpringBootTest(
    classes = [ArcObservableQueryCompositionHostingTests.Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "cratis.arc.correlation-header=X-Arc-Correlation",
        "cratis.arc.tenancy.resolvers=development",
        "cratis.arc.tenancy.fixed-tenant-id=composition-tenant"
    ]
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
internal class ArcObservableQueryCompositionHostingTests {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var fixture: CompositionHostFixture

    @Autowired
    lateinit var pipeline: ObservableQueryPipeline

    private val executor = Executors.newFixedThreadPool(2)
    private val http = HttpClient.newBuilder().executor(executor)
        .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()
    private var socket: CompositionSocket? = null
    private val correlation = UUID.randomUUID()
    private var captured: CompositionCapture? = null

    @AfterEach
    fun closeResources() {
        try {
            socket?.close()
        } finally {
            try {
                runBlocking { withTimeout(5_000) { fixture.state.subscriptionCount.first { it == 0 } } }
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "HTTP client executor must terminate")
            }
        }
        // DirtiesContext closes the real server, transport and application coroutine scope after every test.
    }

    @Test
    fun `GET without wait suppresses the FULL current snapshot after one processing chain`() {
        snapshot("GET", allowed = false)
    }

    @Test
    fun `QUERY without wait suppresses the FULL current snapshot after one processing chain with no-store`() {
        snapshot("QUERY", allowed = false)
    }

    @Test
    fun `GET without wait delivers the transformed FULL current snapshot without a change set`() {
        snapshot("GET", allowed = true)
    }

    @Test
    fun `QUERY without wait delivers the transformed FULL current snapshot without a change set with no-store`() {
        snapshot("QUERY", allowed = true)
    }

    private fun snapshot(method: String, allowed: Boolean) {
        assertEquals(DefaultObservableQueryPipeline::class.java, pipeline.javaClass)
        val step = if (allowed) "A" else "S"
        fixture.state.value = compositionRows(step)
        val request = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:$port$COMPOSITION_ROUTE" + if (method == "GET") "?argument=captured" else "")
        ).timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer composition")
            .header("X-Arc-Correlation", correlation.toString())
        if (method == "GET") request.GET() else request.header("Content-Type", "application/json")
            .method("QUERY", HttpRequest.BodyPublishers.ofString("""{"arguments":{"argument":"captured"}}"""))
        // Each HTTP request opens independently with the pipeline's FULL default, not nullable hub mode.
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(if (allowed) 200 else 202, response.statusCode(), response.body())
        assertEquals(correlation.toString(), response.headers().firstValue("X-Arc-Correlation").orElse(""))
        if (method == "QUERY") assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""))
        val envelope = mapper.readTree(response.body())
        assertEquals(correlation.toString(), envelope.path("correlationId").stringValue())
        assertEquals(mapper.nodeFactory.booleanNode(allowed), envelope.path("isReady"))
        assertAbsent(envelope.path("changeSet"))
        if (allowed) {
            assertTrue(envelope.path("isSuccess").booleanValue(), envelope.toString())
            assertEquals(jsonRows(step), envelope.path("data"))
        } else {
            assertAbsent(envelope.path("data"))
        }
        event("perform", compositionRows(step))
        processed(step, true)
        assertTrue(fixture.events.isEmpty(), "The snapshot must execute exactly one processing chain")
        assertEquals(0, fixture.state.subscriptionCount.value)
    }

    @Test
    fun `hub omitted mode preserves first delivered legacy envelope and delivered baseline after suppression`() {
        assertEquals(DefaultObservableQueryPipeline::class.java, pipeline.javaClass)
        val listener = CompositionSocket(mapper)
        socket = listener
        listener.socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer composition")
            .header("X-Arc-Correlation", correlation.toString())
            .buildAsync(URI.create("ws://127.0.0.1:$port/.cratis/queries/ws"), listener).get(5, TimeUnit.SECONDS)
        assertTrue(listener.awaitType("Connected").path("supportsSubscriptionRevisions").booleanValue())
        val subscribe = """{"type":"Subscribe","queryId":"composition","revision":7,"payload":{"queryName":"$COMPOSITION_NAME","arguments":{"argument":"captured"}}}"""
        assertFalse(mapper.readTree(subscribe).path("payload").has("transferMode"))
        listener.send(subscribe)
        // The real hub collects results, never snapshot. Await the suppressed initial StateFlow guard event.
        event("perform", compositionRows("S"))
        processed("S", true)
        fixture.state.value = compositionRows("A")
        processed("A", true)
        val first = query(listener)
        assertEquals(jsonRows("A"), first.path("data"))
        val initialChanges = first.path("changeSet")
        assertTrue(initialChanges.isObject, "Omitted hub transferMode must retain legacy data plus changeSet: $first")
        assertEquals(jsonRows("A"), initialChanges.path("added"))
        assertEquals(mapper.createArrayNode(), initialChanges.path("replaced"))
        assertEquals(mapper.createArrayNode(), initialChanges.path("removed"))
        // Updates follow processing/delivery acknowledgments, so StateFlow cannot conflate an unobserved B.
        fixture.state.value = compositionRows("B")
        processed("B", false)
        fixture.state.value = compositionRows("C")
        processed("C", false)
        val next = query(listener)
        assertEquals(jsonRows("C"), next.path("data"))
        val changes = next.path("changeSet")
        assertTrue(changes.isObject, next.toString())
        assertEquals(mapper.valueToTree<JsonNode>(listOf(CompositionHostItem(4, "C!"))), changes.path("added"))
        assertEquals(mapper.valueToTree<JsonNode>(listOf(CompositionHostItem(1, "C!"))), changes.path("replaced"))
        assertEquals(mapper.valueToTree<JsonNode>(listOf(CompositionHostItem(2, "A!"))), changes.path("removed"))
        assertTrue(fixture.events.isEmpty(), "Only S, A, B, C are processed; no snapshot collection on the hub")
    }

    private fun query(listener: CompositionSocket): JsonNode {
        val message = listener.awaitType("QueryResult")
        assertEquals("composition", message.path("queryId").stringValue())
        assertEquals(7, message.path("revision").intValue())
        val payload = message.path("payload")
        assertEquals(correlation.toString(), payload.path("correlationId").stringValue())
        assertTrue(payload.path("isSuccess").booleanValue(), message.toString())
        assertTrue(payload.path("isReady").booleanValue(), message.toString())
        return payload
    }

    private fun processed(step: String, first: Boolean) {
        event("render", compositionRows(step))
        compositionRows(step).forEach { event("intercept", listOf(it)) }
        event("guard", compositionRows(step).map { it.copy(value = it.value + "!") }, first)
    }

    private fun event(name: String, data: List<CompositionHostItem>, first: Boolean? = null) {
        val event = fixture.events.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("Timed out awaiting $name $data first=$first")
        assertEquals(name, event.name)
        assertEquals(data, event.data)
        assertEquals(first, event.first)
        val context = event.context
        assertEquals(FullyQualifiedQueryName(COMPOSITION_NAME), context.name)
        assertEquals(mapOf("argument" to "captured"), context.arguments)
        assertEquals(correlation, context.correlation)
        assertEquals("composition-tenant", context.tenant)
        assertEquals("composition-tenant", context.namespace)
        assertEquals("composition-user", context.principal.id)
        assertEquals("Composition User", context.principal.name)
        assertTrue(context.principal.isAuthenticated)
        assertTrue(context.principal.isInRole("reader"))
        assertSame(fixture, context.services.resolve(CompositionHostFixture::class.java))
        captured?.let {
            assertSame(it.principal, context.principal)
            assertSame(it.services, context.services)
        } ?: run { captured = context }
    }

    private fun jsonRows(step: String): JsonNode =
        mapper.valueToTree(compositionRows(step).map { it.copy(value = it.value + "!") })

    private fun assertAbsent(node: JsonNode) = assertTrue(node.isNull || node.isMissingNode, node.toString())

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
    class Application {
        @Bean
        fun compositionFixture(): CompositionHostFixture = CompositionHostFixture()

        @Bean
        fun compositionModule(fixture: CompositionHostFixture): ArcArtifactModule =
            object : ArcArtifactModule(emptyList(), listOf(CompositionHostPerformer(fixture))) {}

        @Bean
        fun compositionRenderer(fixture: CompositionHostFixture): BlockingQueryRendererFor<Any> =
            object : BlockingQueryRendererFor<Any> {
                override fun queryType(): Class<Any> = Any::class.java
                override fun renderBlocking(query: Any, current: QueryRendererResult, context: QueryContext): QueryRendererResult {
                    fixture.record("render", (query as List<*>).map { it as CompositionHostItem }, capture(context))
                    return current
                }
            }

        @Bean
        fun compositionInterceptor(fixture: CompositionHostFixture): BlockingReadModelInterceptor<CompositionHostItem> =
            object : BlockingReadModelInterceptor<CompositionHostItem> {
                override fun readModelType(): Class<CompositionHostItem> = CompositionHostItem::class.java
                override fun interceptBlocking(readModel: CompositionHostItem, context: QueryContext): CompositionHostItem {
                    fixture.record("intercept", listOf(readModel), capture(context))
                    return readModel.copy(value = readModel.value + "!")
                }
            }

        @Bean
        fun compositionGuard(fixture: CompositionHostFixture): BlockingObservableQueryEmissionGuard =
            BlockingObservableQueryEmissionGuard { context ->
                val values = (context.data as List<*>).map { it as CompositionHostItem }
                fixture.record("guard", values, CompositionCapture(
                    context.queryName, context.arguments, context.correlationId, context.principal,
                    context.tenantId, context.tenantNamespace, context.serviceResolver
                ), context.isFirstEmission)
                if (values.first().value in setOf("S!", "B!")) {
                    ObservableQueryEmissionVerdict.SUPPRESS
                } else {
                    ObservableQueryEmissionVerdict.ALLOW
                }
            }

        @Bean
        fun compositionAuthentication(): AuthenticationHandler = AuthenticationHandler { context ->
            if (context.header("Authorization") == "Bearer composition") {
                AuthenticationResult.succeeded(
                    ArcPrincipal("Composition User", true, setOf("reader"), "composition-user")
                )
            } else {
                AuthenticationResult.ANONYMOUS
            }
        }
    }
}

private const val COMPOSITION_ROUTE = "/api/fixtures/observable-composition"
private const val COMPOSITION_NAME = "io.cratis.arc.springboot.CompositionHost.observe"

internal data class CompositionHostItem(val id: Int, val value: String)

private fun compositionRows(step: String): List<CompositionHostItem> = when (step) {
    "S" -> listOf(CompositionHostItem(9, step))
    "A" -> listOf(CompositionHostItem(1, step), CompositionHostItem(2, step))
    "B" -> listOf(CompositionHostItem(1, step), CompositionHostItem(3, step))
    "C" -> listOf(CompositionHostItem(1, step), CompositionHostItem(4, step))
    else -> error("Unknown step $step")
}

internal class CompositionHostFixture {
    val state = MutableStateFlow(compositionRows("S"))
    val events = LinkedBlockingQueue<CompositionHostEvent>(32)

    fun record(name: String, data: List<CompositionHostItem>, context: CompositionCapture, first: Boolean? = null) {
        check(events.offer(CompositionHostEvent(name, data, context, first))) { "Processing event queue overflow" }
    }
}

internal data class CompositionHostEvent(
    val name: String,
    val data: List<CompositionHostItem>,
    val context: CompositionCapture,
    val first: Boolean?
)

internal data class CompositionCapture(
    val name: FullyQualifiedQueryName,
    val arguments: Map<String, Any?>,
    val correlation: UUID,
    val principal: ArcPrincipal,
    val tenant: String?,
    val namespace: String?,
    val services: ServiceResolver
)

private fun capture(context: QueryContext) = CompositionCapture(
    context.queryName, context.request.arguments, context.correlationId, context.principal,
    context.tenantId, context.tenantNamespace, context.serviceResolver
)

private class CompositionHostPerformer(private val fixture: CompositionHostFixture) : QueryPerformer {
    override val fullyQualifiedName = FullyQualifiedQueryName(COMPOSITION_NAME)
    override val descriptor = QueryDescriptor(
        "observe", "io.cratis.arc.springboot.CompositionHost", CompositionHostItem::class.java.name,
        parameters = listOf(ParameterDescriptor(
            "argument", TypeShapeDescriptor.value("kotlin.String"), QueryParameterSource.CLIENT
        )),
        routeOptions = RouteOptions(COMPOSITION_ROUTE),
        fullyQualifiedName = COMPOSITION_NAME,
        authorization = AuthorizationMetadata(false, null, listOf("reader"), emptyList()),
        explicitPath = COMPOSITION_ROUTE,
        queryHttpMethod = QueryHttpMethodType.GET,
        transport = QueryTransportType.OBSERVABLE,
        isEnumerable = true
    )

    override suspend fun perform(context: QueryContext): Any {
        fixture.record("perform", fixture.state.value, capture(context))
        return fixture.state
    }
}

private class CompositionSocket(private val mapper: ObjectMapper) : WebSocket.Listener, AutoCloseable {
    var socket: WebSocket? = null
    private val messages = LinkedBlockingQueue<JsonNode>(32)
    private val failure = AtomicReference<Throwable?>()
    private val closed = CompletableFuture<Int>()
    private val text = StringBuilder()

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
        try {
            check(text.length + data.length <= 65_536) { "WebSocket test frame exceeded its bound" }
            text.append(data)
            if (last) {
                check(messages.offer(mapper.readTree(text.toString()))) { "WebSocket test queue overflow" }
                text.setLength(0)
            }
            webSocket.request(1)
        } catch (exception: Exception) {
            onError(webSocket, exception)
        }
        return CompletableFuture.completedFuture(null)
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        failure.compareAndSet(null, error)
        closed.completeExceptionally(error)
        webSocket.abort()
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
        closed.complete(statusCode)
        return CompletableFuture.completedFuture(null)
    }

    fun send(json: String) {
        requireNotNull(socket).sendText(json, true).get(5, TimeUnit.SECONDS)
    }

    fun awaitType(expected: String): JsonNode {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            failure.get()?.let { throw AssertionError("WebSocket failed while awaiting $expected", it) }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            val message = messages.poll(remaining, TimeUnit.NANOSECONDS) ?: break
            val type = message.path("type").stringValue()
            if (type == "Ping") {
                send("""{"type":"Pong","timestamp":${message.path("timestamp").asLong()}}""")
                continue
            }
            assertEquals(expected, type, "Unexpected hub frame: $message")
            return message
        }
        throw AssertionError("Timed out awaiting $expected; closed=${closed.isDone}", failure.get())
    }

    override fun close() {
        val current = socket ?: return
        try {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
            assertEquals(WebSocket.NORMAL_CLOSURE, closed.get(5, TimeUnit.SECONDS))
            assertNull(failure.get(), "WebSocket listener errors must not be swallowed")
            assertTrue(messages.none { it.path("type").stringValue() != "Ping" }, "Unexpected undelivered hub frames: $messages")
        } finally {
            if (!current.isInputClosed || !current.isOutputClosed) current.abort()
        }
    }
}
