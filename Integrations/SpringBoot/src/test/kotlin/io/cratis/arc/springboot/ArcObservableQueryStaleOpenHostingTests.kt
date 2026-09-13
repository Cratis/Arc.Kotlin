// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryFilter
import io.cratis.arc.queries.QueryHealthTracker
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.results.QueryResult
import java.io.BufferedReader
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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

/** Both real hub routes execute the shared runner, with a deliberately non-cooperative opening filter. */
@SpringBootTest(
    classes = [ArcObservableQueryStaleOpenHostingTests.Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["cratis.arc.observable-queries.keep-alive-interval=0"]
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ArcObservableQueryStaleOpenHostingTests {
    @LocalServerPort
    var port: Int = 0
    @Autowired
    lateinit var mapper: ObjectMapper
    @Autowired
    lateinit var state: OpeningState
    @Autowired
    lateinit var health: QueryHealthTracker
    @Autowired
    lateinit var transport: ArcObservableQueryTransport

    @AfterAll
    fun closeQuietSseConnections() {
        // With heartbeats disabled a closed client may remain invisible to the servlet until its next write.
        transport.close()
    }

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @ParameterizedTest
    @CsvSource("sse,true,Error", "sse,true,Unauthorized", "sse,false,Error", "sse,false,Unauthorized",
        "websocket,true,Error", "websocket,true,Unauthorized", "websocket,false,Error", "websocket,false,Unauthorized")
    fun `late opening failure cannot terminate replacement or unregister its health`(route: String, revised: Boolean, kind: String) {
        val opening = state.begin(kind)
        Hub(route).use { hub ->
            hub.subscribe("query", revision(revised, 1), "opening")
            val suspended = opening.entered.get(5, TimeUnit.SECONDS)
            hub.subscribe("query", revision(revised, 2), "live")
            opening.emit("replacement ready")
            hub.assertData("query", revision(revised, 2), "replacement ready")
            val replacement = subscription(hub.id, "query")
            assertEquals("stale-open.live", replacement.queryIdentifier)
            assertNotNull(replacement.lastDataServedAt)
            assertTrue(suspended.job.isCancelled, "replacement must cancel the old job before releasing its opening")
            opening.releaseAndJoin(suspended)
            // Job completion includes the transport finally block, not merely the filter's return.
            assertSame(replacement, subscription(hub.id, "query"), "late finally must not remove replacement health")
            opening.emit("replacement continues")
            // This ordered frame is also a fence: any stale terminal enqueued by the completed old job comes first.
            hub.assertData("query", revision(revised, 2), "replacement continues")
            assertEquals(0, opening.oldInvocations.get())
            assertEquals(1, opening.liveInvocations.get())
            assertEquals(1, health.snapshot().connections.single { it.connectionId == hub.id }.subscriptions.size)
        }
    }

    @ParameterizedTest
    @CsvSource("sse,true,Error", "sse,true,Unauthorized", "sse,false,Error", "sse,false,Unauthorized",
        "websocket,true,Error", "websocket,true,Unauthorized", "websocket,false,Error", "websocket,false,Unauthorized")
    fun `late opening failure after unsubscribe emits no terminal and leaves another subscription healthy`(route: String, revised: Boolean, kind: String) {
        val opening = state.begin(kind)
        Hub(route).use { hub ->
            hub.subscribe("query", revision(revised, 1), "opening")
            val suspended = opening.entered.get(5, TimeUnit.SECONDS)
            hub.unsubscribe("query", revision(revised, 2))
            // On WebSocket this following subscribe is a server-side processing barrier for unsubscribe.
            hub.subscribe("witness", revision(revised, 3), "live")
            opening.emit("witness ready")
            hub.assertData("witness", revision(revised, 3), "witness ready")
            val witness = subscription(hub.id, "witness")
            assertTrue(suspended.job.isCancelled)
            assertFalse(health.snapshot().connections.single { it.connectionId == hub.id }.subscriptions.any { it.subscriptionId == "query" })
            opening.releaseAndJoin(suspended)
            assertSame(witness, subscription(hub.id, "witness"))
            opening.emit("witness continues")
            hub.assertData("witness", revision(revised, 3), "witness continues")
            assertEquals(listOf("witness"), health.snapshot().connections.single { it.connectionId == hub.id }.subscriptions.map { it.subscriptionId })
            assertEquals(0, opening.oldInvocations.get())
            assertEquals(1, opening.liveInvocations.get())
        }
    }

    @ParameterizedTest
    @CsvSource("sse,true,Error", "sse,true,Unauthorized", "sse,false,Error", "sse,false,Unauthorized",
        "websocket,true,Error", "websocket,true,Unauthorized", "websocket,false,Error", "websocket,false,Unauthorized")
    fun `current opening failure retains its terminal envelope and revision without closing the hub`(route: String, revised: Boolean, kind: String) {
        val opening = state.begin(kind)
        Hub(route).use { hub ->
            hub.subscribe("query", revision(revised, 1), "opening")
            val suspended = opening.entered.get(5, TimeUnit.SECONDS)
            assertFalse(suspended.job.isCancelled)
            opening.releaseAndJoin(suspended)
            val expected = linkedMapOf<String, Any>("type" to kind, "queryId" to "query")
            revision(revised, 1)?.let { expected["revision"] = it }
            if (kind == "Error") expected["payload"] = "opening rejected"
            assertEquals(mapper.readTree(mapper.writeValueAsString(expected)), hub.next(), "current failures must not be suppressed or reshaped")
            assertFalse(health.snapshot().connections.any { it.connectionId == hub.id })
            hub.subscribe("witness", revision(revised, 2), "live")
            opening.emit("hub continues")
            hub.assertData("witness", revision(revised, 2), "hub continues")
            assertEquals("stale-open.live", subscription(hub.id, "witness").queryIdentifier)
            assertEquals(0, opening.oldInvocations.get())
            assertEquals(1, opening.liveInvocations.get())
        }
    }

    private fun revision(revised: Boolean, value: Long): Long? = value.takeIf { revised }

    private fun subscription(connection: String, query: String) = health.snapshot().connections
        .single { it.connectionId == connection }.subscriptions.single { it.subscriptionId == query }

    private inner class Hub(private val route: String) : AutoCloseable {
        private val listener = SocketListener()
        private var socket: WebSocket? = null
        private var reader: BufferedReader? = null
        val id: String

        init {
            if (route == "sse") {
                val response = client.sendAsync(
                    HttpRequest.newBuilder(uri(OBSERVABLE_QUERY_SSE_ROUTE)).GET().build(), HttpResponse.BodyHandlers.ofInputStream()
                ).get(5, TimeUnit.SECONDS)
                assertEquals(200, response.statusCode())
                reader = response.body().bufferedReader()
            } else {
                socket = client.newWebSocketBuilder().buildAsync(
                    URI.create("ws://127.0.0.1:$port$OBSERVABLE_QUERY_WS_ROUTE"), listener
                ).get(5, TimeUnit.SECONDS)
            }
            val connected = next()
            assertEquals("Connected", connected.path("type").stringValue())
            assertTrue(connected.path("supportsSubscriptionRevisions").booleanValue())
            id = connected.path("payload").stringValue()
        }

        fun subscribe(query: String, revision: Long?, name: String) {
            val request = mapOf("queryName" to "stale-open.$name", "transferMode" to "full")
            send("Subscribe", query, revision, request)
        }

        fun unsubscribe(query: String, revision: Long?) = send("Unsubscribe", query, revision, null)

        private fun send(type: String, query: String, revision: Long?, request: Any?) {
            val body = linkedMapOf<String, Any>("queryId" to query)
            revision?.let { body["revision"] = it }
            if (route == "sse") {
                body["connectionId"] = id
                request?.let { body["request"] = it }
                val path = if (type == "Subscribe") OBSERVABLE_QUERY_SSE_SUBSCRIBE_ROUTE else OBSERVABLE_QUERY_SSE_UNSUBSCRIBE_ROUTE
                val response = client.sendAsync(
                    HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString()
                ).get(5, TimeUnit.SECONDS)
                assertEquals(200, response.statusCode(), response.body())
            } else {
                body["type"] = type
                request?.let { body["payload"] = it }
                socket!!.sendText(mapper.writeValueAsString(body), true).get(5, TimeUnit.SECONDS)
            }
        }

        fun next(): JsonNode {
            val text = if (route == "sse") CompletableFuture.supplyAsync {
                val input = requireNotNull(reader)
                var line = input.readLine()
                while (line != null && !line.startsWith("data: ")) line = input.readLine()
                requireNotNull(line) { "SSE ended before the expected frame" }.removePrefix("data: ")
            }.get(5, TimeUnit.SECONDS) else requireNotNull(listener.frames.poll(5, TimeUnit.SECONDS)) { "No WebSocket frame" }
            return mapper.readTree(text)
        }

        fun assertData(query: String, revision: Long?, value: String) {
            val frame = next()
            assertEquals("QueryResult", frame.path("type").stringValue(), frame.toString())
            assertEquals(query, frame.path("queryId").stringValue())
            if (revision == null) assertFalse(frame.has("revision")) else assertEquals(revision, frame.path("revision").longValue())
            assertEquals(value, frame.path("payload").path("data").stringValue())
            assertTrue(frame.path("payload").path("isAuthorized").booleanValue())
            assertFalse(frame.path("payload").path("hasExceptions").booleanValue())
            // A health snapshot after the frame's network write can precede recordDataServed;
            // the performer's next receive signals that the whole prior emission has returned.
            state.current.emissionReturned.receiveBlocking()
        }

        override fun close() {
            state.current.releaseIfNeeded()
            socket?.abort()
            reader?.close()
            state.current.values.close()
        }
    }

    private fun uri(path: String): URI = URI.create("http://127.0.0.1:$port$path")

    private class SocketListener : WebSocket.Listener {
        val frames = LinkedBlockingQueue<String>()
        private val partial = StringBuilder()
        override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            partial.append(data)
            if (last) { frames.add(partial.toString()); partial.setLength(0) }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }
    }

    class OpeningState {
        lateinit var current: Opening
        fun begin(kind: String): Opening = Opening(kind).also { current = it }
    }

    class Opening(val kind: String) {
        val entered = CompletableFuture<SuspendedOpening>()
        val resumed = CompletableFuture<Unit>()
        val values = Channel<String>(Channel.UNLIMITED)
        val emissionReturned = LinkedBlockingQueue<Unit>()
        val oldInvocations = AtomicInteger()
        val liveInvocations = AtomicInteger()
        private val released = java.util.concurrent.atomic.AtomicBoolean()

        suspend fun reject(context: QueryContext): QueryResult<*> {
            val job = requireNotNull(currentCoroutineContext()[Job])
            // suspendCoroutine intentionally does not cooperate with cancellation. Only the test
            // resumes it, AFTER replacement/unsubscribe; ordinary cancellable suspension cannot expose this bug.
            suspendCoroutine<Unit> { continuation -> entered.complete(SuspendedOpening(job, continuation)) }
            resumed.complete(Unit)
            return if (kind == "Unauthorized") QueryResult.unauthorized<Any?>(context.correlationId)
                else QueryResult.error<Any?>(context.correlationId, "opening rejected")
        }

        fun emit(value: String) { check(values.trySend(value).isSuccess) }

        fun releaseAndJoin(suspended: SuspendedOpening) {
            if (released.compareAndSet(false, true)) suspended.continuation.resume(Unit)
            resumed.get(5, TimeUnit.SECONDS)
            runBlocking { withTimeout(5000) { suspended.job.join() } }
        }

        fun releaseIfNeeded() { if (entered.isDone) releaseAndJoin(entered.get(5, TimeUnit.SECONDS)) }
    }

    class SuspendedOpening(val job: Job, val continuation: Continuation<Unit>)

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = [ServletWebSecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class])
    class Application {
        @Bean
        fun openingState(): OpeningState = OpeningState()
        @Bean
        fun openingFilter(state: OpeningState): QueryFilter = QueryFilter { context ->
            if (context.queryName.value == "stale-open.opening") state.current.reject(context)
            else QueryResult.success<Any?>(context.correlationId)
        }
        @Bean
        fun openingModule(state: OpeningState): ArcArtifactModule = object : ArcArtifactModule(
            emptyList(), listOf(OpeningPerformer("opening", state), OpeningPerformer("live", state))
        ) {}
    }

    private class OpeningPerformer(private val name: String, private val state: OpeningState) : QueryPerformer {
        override val fullyQualifiedName = FullyQualifiedQueryName("stale-open.$name")
        override val descriptor = QueryDescriptor(
            name, "stale-open", "kotlin.String", fullyQualifiedName = fullyQualifiedName.value,
            authorization = AuthorizationMetadata(allowAnonymous = true), transport = QueryTransportType.OBSERVABLE
        )
        override suspend fun perform(context: QueryContext): Any {
            val opening = state.current
            check(name == "live") { opening.oldInvocations.incrementAndGet(); "Rejected opening must not invoke its performer" }
            opening.liveInvocations.incrementAndGet()
            return kotlinx.coroutines.flow.flow {
                for (value in opening.values) {
                    emit(value)
                    opening.emissionReturned.add(Unit)
                }
            }
        }
    }
}

private fun LinkedBlockingQueue<Unit>.receiveBlocking() {
    assertNotNull(poll(5, TimeUnit.SECONDS), "emission did not return through health recording")
}
