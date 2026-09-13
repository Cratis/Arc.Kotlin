// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.BlockingObservableQueryEmissionGuard
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.DefaultObservableQueryEmissionGuards
import io.cratis.arc.queries.DefaultObservableQueryPipeline
import io.cratis.arc.queries.DefaultQueryHealthTracker
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.ObservableQueryEmissionVerdict
import io.cratis.arc.queries.ObservableQueryOpenResult
import io.cratis.arc.queries.ObservableQueryPipeline
import io.cratis.arc.queries.ObservableQueryTransferMode
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.tenancy.TenantIdResolver
import jakarta.servlet.AsyncEvent
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CompletableFuture
import java.io.IOException
import jakarta.servlet.AsyncContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.springframework.core.convert.support.DefaultConversionService
import org.springframework.mock.web.MockAsyncContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

internal class ArcObservableQueryTerminalDrainTests {
    @ParameterizedTest
    @ValueSource(strings = ["finite", "openingFailure", "guardDenied"])
    fun `direct SSE producer completion keeps accepted terminal frame open until written`(scenario: String) {
        Fixture(scenario).use { fixture ->
            val request = MockHttpServletRequest("GET", "/terminal").apply {
                isAsyncSupported = true
                addHeader("Accept", "text/event-stream")
            }
            val response = object : MockHttpServletResponse() {
                override fun getOutputStream(): ServletOutputStream = fixture.output
            }
            fixture.transport.directHttpHandler(fixture.performer).handleRequest(request, response)
            try {
                assertTrue(fixture.producerDone.await(5, TimeUnit.SECONDS), "producer did not finish")
                assertEquals(1, fixture.transport.activeConnectionCount, "accepted frame must retain transport lease while write is held")
                assertEquals(1, fixture.health.snapshot().totalConnections)
                assertTrue(fixture.output.entered.await(5, TimeUnit.SECONDS))
                assertTrue(fixture.output.frames.isEmpty())
                fixture.output.release.countDown()
                val frame = requireNotNull(fixture.output.frames.poll(5, TimeUnit.SECONDS))
                val envelope = fixture.mapper.readTree(frame.removePrefix("data: ").trim())
                when (scenario) {
                    "finite" -> assertEquals("one", envelope.path("data").stringValue())
                    "openingFailure" -> assertTrue(envelope.path("hasExceptions").booleanValue())
                    "guardDenied" -> assertEquals(false, envelope.path("isAuthorized").booleanValue())
                }
                fixture.joinChildren()
                assertEquals(0, fixture.transport.activeConnectionCount)
                assertEquals(0, fixture.health.snapshot().totalConnections)
            } finally {
                fixture.output.release.countDown()
                val async = request.asyncContext as MockAsyncContext
                async.listeners.toList().forEach { it.onError(AsyncEvent(async)) }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["finite", "openingFailure", "guardDenied"])
    fun `direct WebSocket producer completion keeps accepted terminal frame open until written`(scenario: String) {
        Fixture(scenario).use { fixture ->
            val socket = GatedSocket()
            socket.attributes["io.cratis.arc.observable.handshake"] = ArcObservableHandshake(
                ArcPrincipal(), null, UUID.randomUUID(), emptyMap(), null
            )
            socket.attributes["io.cratis.arc.observable.lease"] = requireNotNull(fixture.transport.tryReserveConnection())
            val handler = ArcDirectObservableWebSocketHandler(fixture.performer, fixture.transport, fixture.scope, fixture.properties)
            try {
                handler.afterConnectionEstablished(socket.session)
                assertTrue(fixture.producerDone.await(5, TimeUnit.SECONDS), "producer did not finish")
                assertEquals(1, fixture.transport.activeConnectionCount, "accepted frame must retain lease while write is held")
                assertTrue(socket.closes.isEmpty())
                assertTrue(socket.entered.await(5, TimeUnit.SECONDS))
                assertTrue(socket.frames.isEmpty())
                socket.release.countDown()
                val frame = fixture.mapper.readTree(requireNotNull(socket.frames.poll(5, TimeUnit.SECONDS)))
                assertEquals("Data", frame.path("type").stringValue())
                when (scenario) {
                    "finite" -> assertEquals("one", frame.path("data").path("data").stringValue())
                    "openingFailure" -> assertTrue(frame.path("data").path("hasExceptions").booleanValue())
                    "guardDenied" -> assertEquals(false, frame.path("data").path("isAuthorized").booleanValue())
                }
                assertEquals(CloseStatus.NORMAL, socket.closes.poll(5, TimeUnit.SECONDS))
                fixture.joinChildren()
                assertEquals(0, fixture.transport.activeConnectionCount)
                assertEquals(0, fixture.health.snapshot().totalConnections)
            } finally {
                socket.release.countDown()
                handler.afterConnectionClosed(socket.session, CloseStatus.NORMAL)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["sse", "websocket"])
    fun `finish drains several accepted frames in order and rejects late resources`(protocol: String) {
        WriterFixture(protocol).use { fixture ->
            assertTrue(fixture.send("one"))
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
            assertTrue(fixture.send("two"))
            assertTrue(fixture.send("terminalUnauthorized"))
            val heartbeat = requireNotNull(fixture.scope.tryLaunch(start = CoroutineStart.LAZY) { error("heartbeat started") })
            fixture.attachHeartbeat(heartbeat)
            repeat(3) { fixture.finish() }
            assertFalse(fixture.send("late"))
            val collector = requireNotNull(fixture.scope.tryLaunch(start = CoroutineStart.LAZY) { error("collector started") })
            val lateHeartbeat = requireNotNull(fixture.scope.tryLaunch(start = CoroutineStart.LAZY) { error("late heartbeat started") })
            fixture.attach(collector)
            fixture.attachHeartbeat(lateHeartbeat)
            collector.start()
            lateHeartbeat.start()
            runBlocking { withTimeout(5000) { heartbeat.join(); collector.join(); lateHeartbeat.join() } }
            assertTrue(heartbeat.isCancelled)
            assertTrue(collector.isCancelled)
            assertTrue(lateHeartbeat.isCancelled)
            assertEquals(1, fixture.leases.get())
            assertEquals(0, fixture.removals.get())
            assertTrue(fixture.closes.isEmpty())
            fixture.release.countDown()
            assertEquals(listOf("one", "two", "terminalUnauthorized"), (1..3).map {
                fixture.payload(requireNotNull(fixture.frames.poll(5, TimeUnit.SECONDS)))
            })
            assertEquals(CloseStatus.NORMAL, fixture.closes.poll(5, TimeUnit.SECONDS))
            fixture.joinChildren()
            repeat(3) { fixture.finish(); fixture.abort() }
            assertEquals(0, fixture.leases.get())
            assertEquals(1, fixture.removals.get())
            assertTrue(fixture.closes.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["sse", "websocket"])
    fun `overload aborts instead of draining and preserves the first socket close status`(protocol: String) {
        WriterFixture(protocol, capacity = 1).use { fixture ->
            assertTrue(fixture.send("held"))
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
            assertTrue(fixture.send("queued"))
            assertFalse(fixture.send("overflow"))
            repeat(3) { fixture.finish(); fixture.abort() }
            assertEquals(0, fixture.leases.get())
            assertEquals(1, fixture.removals.get())
            val status = requireNotNull(fixture.closes.poll(5, TimeUnit.SECONDS))
            assertEquals(if (protocol == "websocket") 1013 else 1000, status.code)
            fixture.release.countDown()
            fixture.joinChildren()
            assertEquals(listOf("held"), fixture.frames.toList().map(fixture::payload))
            assertTrue(fixture.closes.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["sse", "websocket"])
    fun `disconnect while draining aborts queued frames and cancels upstream`(protocol: String) {
        WriterFixture(protocol).use { fixture ->
            val upstreamEntered = CompletableFuture<Unit>()
            val upstream = requireNotNull(fixture.scope.tryLaunch(start = CoroutineStart.LAZY) {
                upstreamEntered.complete(Unit)
                awaitCancellation()
            })
            fixture.attach(upstream)
            upstream.start()
            upstreamEntered.get(5, TimeUnit.SECONDS)
            assertTrue(fixture.send("held"))
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
            assertTrue(fixture.send("discarded"))
            fixture.finish()
            assertTimeoutPreemptively(Duration.ofSeconds(1)) { fixture.disconnect() }
            runBlocking { withTimeout(5000) { upstream.join() } }
            assertTrue(upstream.isCancelled)
            assertEquals(0, fixture.leases.get())
            assertEquals(1, fixture.removals.get())
            assertEquals(CloseStatus.NORMAL, fixture.closes.poll(5, TimeUnit.SECONDS))
            fixture.release.countDown()
            fixture.joinChildren()
            assertEquals(listOf("held"), fixture.frames.toList().map(fixture::payload))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["sse", "websocket"])
    fun `drain deadline returns capacity without waiting for blocking write or native close`(protocol: String) {
        WriterFixture(protocol, timeoutMillis = 50, holdClose = true).use { fixture ->
            assertTrue(fixture.send("held"))
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
            assertTrue(fixture.send("discarded"))
            assertTimeoutPreemptively(Duration.ofSeconds(1)) { fixture.finish() }
            assertTrue(fixture.closeEntered.await(5, TimeUnit.SECONDS), "deadline did not request native closure")
            assertEquals(0, fixture.leases.get())
            assertEquals(1, fixture.removals.get())
            assertFalse(fixture.send("late"))
            assertTimeoutPreemptively(Duration.ofSeconds(1)) { repeat(3) { fixture.finish(); fixture.abort() } }
            // A cancelled coroutine does not terminate blocking container I/O. Both gates are
            // still held; release them explicitly before joining the application-owned jobs.
            assertTrue(fixture.frames.isEmpty())
            assertTrue(fixture.closes.isEmpty())
            fixture.release.countDown()
            fixture.releaseClose.countDown()
            fixture.joinChildren()
            assertEquals(listOf("held"), fixture.frames.toList().map(fixture::payload))
            assertEquals(1, fixture.closes.size)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["sse", "websocket"])
    fun `application shutdown aborts a draining blocked writer without awaiting its thread`(protocol: String) {
        WriterFixture(protocol).use { fixture ->
            assertTrue(fixture.send("held"))
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS))
            assertTrue(fixture.send("discarded"))
            fixture.finish()
            assertTimeoutPreemptively(Duration.ofSeconds(1)) { fixture.scope.close() }
            assertEquals(CloseStatus.NORMAL, fixture.closes.poll(5, TimeUnit.SECONDS))
            assertEquals(0, fixture.leases.get())
            assertEquals(1, fixture.removals.get())
            fixture.release.countDown()
            fixture.joinChildren()
            assertEquals(listOf("held"), fixture.frames.toList().map(fixture::payload))
        }
    }

    @Test
    fun `direct SSE transport shutdown aborts an accepted frame still being drained`() {
        Fixture("finite").use { fixture ->
            val request = MockHttpServletRequest("GET", "/terminal").apply {
                isAsyncSupported = true
                addHeader("Accept", "text/event-stream")
            }
            val response = object : MockHttpServletResponse() {
                override fun getOutputStream(): ServletOutputStream = fixture.output
            }
            fixture.transport.directHttpHandler(fixture.performer).handleRequest(request, response)
            assertTrue(fixture.producerDone.await(5, TimeUnit.SECONDS))
            assertTrue(fixture.output.entered.await(5, TimeUnit.SECONDS))
            fixture.transport.close()
            assertEquals(0, fixture.transport.activeConnectionCount)
            assertEquals(0, fixture.health.snapshot().totalConnections)
        }
    }

    private class WriterFixture(
        private val protocol: String,
        capacity: Int = 4,
        timeoutMillis: Long = 30_000,
        holdClose: Boolean = false
    ) : AutoCloseable {
        private val fixture = Fixture("finite")
        val scope get() = fixture.scope
        val leases = AtomicInteger(1)
        val removals = AtomicInteger()
        private val socket = GatedSocket(holdClose)
        val entered get() = if (protocol == "sse") fixture.output.entered else socket.entered
        val release get() = if (protocol == "sse") fixture.output.release else socket.release
        val frames get() = if (protocol == "sse") fixture.output.frames else socket.frames
        val closes get() = socket.closes
        val closeEntered get() = socket.closeEntered
        val releaseClose get() = socket.releaseClose
        private val async = mock(AsyncContext::class.java)
        private val stream: ServletSseStream?
        private val writer: ArcSocketWriter?
        init {
            doAnswer {
                socket.complete(CloseStatus.NORMAL)
                null
            }.`when`(async).complete()
            if (protocol == "sse") {
                val response = object : MockHttpServletResponse() {
                    override fun getOutputStream(): ServletOutputStream = fixture.output
                }
                stream = ServletSseStream(async, response, ConnectionLease(leases), capacity, scope, timeoutMillis)
                writer = null
                stream.attachOwner { removals.incrementAndGet() }
                stream.start()
            } else {
                stream = null
                writer = ArcSocketWriter(socket.session, fixture.transport, capacity, ConnectionLease(leases), scope, timeoutMillis)
                writer.attachOwner { removals.incrementAndGet() }
                writer.start()
            }
        }
        fun send(value: String): Boolean = stream?.send(fixture.mapper.writeValueAsString(value)) ?: writer!!.send(value)
        fun finish() { stream?.finish() ?: writer!!.finish() }
        fun abort() { stream?.close() ?: writer!!.close() }
        fun disconnect() { stream?.onError(AsyncEvent(async, IOException("disconnected"))) ?: writer!!.close() }
        fun attach(job: Job) { stream?.attach(job) ?: writer!!.attach(job) }
        fun attachHeartbeat(job: Job) { stream?.attachHeartbeat(job) ?: writer!!.attachHeartbeat(job) }
        fun payload(value: String): String = fixture.mapper.readTree(value.removePrefix("data: ").trim()).stringValue()
        fun joinChildren() = fixture.joinChildren()
        override fun close() {
            release.countDown()
            releaseClose.countDown()
            abort()
            fixture.close()
        }
    }

    private class GatedSocket(holdClose: Boolean = false) {
        val session = mock(WebSocketSession::class.java)
        val attributes = mutableMapOf<String, Any>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val frames = LinkedBlockingQueue<String>()
        val closes = LinkedBlockingQueue<CloseStatus>()
        val open = AtomicBoolean(true)
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(if (holdClose) 1 else 0)
        fun complete(status: CloseStatus) {
            closeEntered.countDown()
            check(releaseClose.await(5, TimeUnit.SECONDS))
            open.set(false)
            closes.add(status)
        }
        init {
            `when`(session.id).thenReturn("terminal")
            `when`(session.attributes).thenReturn(attributes)
            `when`(session.isOpen).thenAnswer { open.get() }
            doAnswer { invocation ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                frames.add(invocation.getArgument<TextMessage>(0).payload)
                null
            }.`when`(session).sendMessage(any())
            doAnswer { invocation ->
                complete(invocation.getArgument(0))
                null
            }.`when`(session).close(any())
        }
    }

    private class Fixture(scenario: String) : AutoCloseable {
        val scope = ArcApplicationCoroutineScope(2, 8)
        val mapper = ArcObjectMapper.create()
        val health = DefaultQueryHealthTracker()
        val output = GatedOutput()
        val producerDone = CountDownLatch(1)
        val properties = ArcProperties().apply { observableQueries.keepAliveInterval = Duration.ZERO }
        val performer = object : QueryPerformer {
            override val fullyQualifiedName = FullyQualifiedQueryName("terminal.observe")
            override val descriptor = QueryDescriptor("observe", "terminal", "kotlin.String", transport = QueryTransportType.OBSERVABLE)
            override suspend fun perform(context: QueryContext): Any {
                if (scenario == "openingFailure") error("opening failed")
                return flowOf("one")
            }
        }
        private val registry = ConcurrentQueryPerformerRegistry().apply { register(performer) }
        private val delegate = DefaultObservableQueryPipeline(registry, emissionGuards = DefaultObservableQueryEmissionGuards(listOf(
            BlockingObservableQueryEmissionGuard {
                if (scenario == "guardDenied") ObservableQueryEmissionVerdict.DENY_AND_TERMINATE else ObservableQueryEmissionVerdict.ALLOW
            }
        )))
        private val pipeline = object : ObservableQueryPipeline {
            override suspend fun open(
                request: QueryRequest,
                options: QueryExecutionOptions,
                transferMode: ObservableQueryTransferMode?,
                keyExtractor: ((Any) -> Any?)?
            ): ObservableQueryOpenResult {
                currentCoroutineContext()[Job]!!.invokeOnCompletion { producerDone.countDown() }
                return delegate.open(request, options, transferMode, keyExtractor)
            }
        }
        val transport = ArcObservableQueryTransport(
            registry, pipeline, mock(ServiceResolver::class.java),
            ArcQueryRequestBinder(mapper, DefaultConversionService(), javaClass.classLoader), mapper, scope, properties, true,
            ArcPrincipalFactory { _, _ -> ArcPrincipal() },
            ArcTenantResolutionService(TenantIdResolver { null }, TenantAccessEvaluator { _, _ -> true }, properties), health
        )
        fun joinChildren() = runBlocking {
            withTimeout(5000) { scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
        }
        override fun close() {
            output.release.countDown()
            transport.close()
            scope.close()
            runBlocking { withTimeout(5000) { scope.coroutineContext[Job]!!.join() } }
        }
    }

    private class GatedOutput : ServletOutputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val frames = LinkedBlockingQueue<String>()
        override fun isReady(): Boolean = true
        override fun setWriteListener(listener: WriteListener) = error("Expected blocking writer")
        override fun write(value: Int) = error("Expected complete frame")
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            frames.add(String(bytes, offset, length, Charsets.UTF_8))
        }
    }
}
