// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.DefaultObservableQueryPipeline
import io.cratis.arc.queries.DefaultQueryHealthTracker
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.ObservableQuerySubscriptionRequest
import io.cratis.arc.queries.QueryHealthTracker
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QuerySubscriptionMetadata
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.tenancy.TenantIdResolver
import jakarta.servlet.AsyncEvent
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.springframework.core.convert.support.DefaultConversionService
import org.springframework.mock.web.MockAsyncContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule

internal class ArcObservableQueryLifecycleTests {
    @ParameterizedTest
    @ValueSource(strings = ["timeout", "error", "completion"])
    fun `servlet terminal callbacks cancel a quiet upstream and return all capacity`(terminal: String) {
        Fixture().use { fixture ->
            val hub = fixture.open()
            fixture.activate(hub)
            fixture.end(hub, terminal)
            fixture.assertCancelled(hub)
            repeat(3) { fixture.end(hub, terminal) }
            assertEquals(1, fixture.removals.get())
            fixture.assertCapacityReturned()
        }
    }

    @Test
    fun `writer IOException cancels upstream without requiring another emission`() {
        Fixture().use { fixture ->
            val hub = fixture.open()
            hub.output.failQueryWrite = true
            fixture.activate(hub, awaitFrame = false)
            assertTrue(hub.output.queryWriteEntered.await(5, TimeUnit.SECONDS))
            hub.output.releaseQueryWrite.countDown()
            fixture.assertCancelled(hub)
            assertEquals(1, fixture.removals.get())
            fixture.assertCapacityReturned()
        }
    }

    @Test
    fun `transport shutdown cancels active subscriptions and rejects old connection identifiers`() {
        Fixture().use { fixture ->
            val hub = fixture.open()
            fixture.activate(hub)
            fixture.transport.close()
            fixture.assertCancelled(hub)
            fixture.transport.close()
            assertEquals(1, fixture.removals.get())
            fixture.assertAdmissionReturned()
        }
    }

    @Test
    fun `application scope shutdown also closes the quiet SSE connection with heartbeats disabled`() {
        Fixture().use { fixture ->
            val hub = fixture.open()
            fixture.activate(hub)
            fixture.scope.close()
            runBlocking { withTimeout(5000) { fixture.scope.coroutineContext[Job]!!.join() } }
            fixture.assertCancelled(hub)
            assertEquals(1, fixture.removals.get())
        }
    }

    @Test
    fun `heartbeat admission failure tears down the newly registered hub exactly once`() {
        Fixture(heartbeat = true).use { fixture ->
            val reservations = fixture.reserveAllAdmission()
            try {
                val hub = fixture.open(awaitConnected = false)
                assertEquals(0, fixture.transport.activeConnectionCount)
                assertEquals(404, fixture.subscribe(hub).status)
                assertEquals(0, fixture.health.snapshot().totalConnections)
                assertEquals(1, fixture.removals.get())
                repeat(2) { fixture.end(hub, "error") }
                assertEquals(1, fixture.removals.get())
            } finally {
                reservations.forEach(Job::cancel)
                runBlocking { reservations.forEach { it.join() } }
            }
            fixture.assertCapacityReturned()
        }
    }

    @Test
    fun `disconnect after POST lookup prevents stale reference from accepting an operation`() {
        Fixture().use { fixture ->
            val hub = fixture.open()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.onPrincipal = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val response = CompletableFuture.supplyAsync { fixture.subscribe(hub) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                fixture.end(hub, "timeout")
            } finally {
                release.countDown()
            }
            assertEquals(404, response.get(5, TimeUnit.SECONDS).status)
            fixture.onPrincipal = {}
            fixture.assertNoWork()
            fixture.assertCapacityReturned()
        }
    }

    @Test
    fun `disconnect during health registration cancels a later attached job before it starts`() {
        Fixture().use { fixture ->
            val hub = fixture.open()
            fixture.onRegistered = { fixture.end(hub, "error") }
            fixture.subscribe(hub)
            // Join every admitted job, including a reservation attached after the callback.
            runBlocking { withTimeout(5000) { fixture.scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } } }
            fixture.assertNoWork()
            assertEquals(404, fixture.subscribe(hub).status)
            fixture.assertCapacityReturned()
        }
    }

    @Test
    fun `close before owner and job attachment closes each late resource without starting it`() {
        val scope = ArcApplicationCoroutineScope(1, 3)
        val response = MockHttpServletResponse()
        val async = MockAsyncContext(MockHttpServletRequest(), response)
        val count = AtomicInteger(1)
        val ownerCloses = AtomicInteger()
        val executions = AtomicInteger()
        val stream = ServletSseStream(async, response, ConnectionLease(count), 1, scope)
        try {
            stream.close()
            stream.attachOwner { ownerCloses.incrementAndGet() }
            val collector = requireNotNull(scope.tryLaunch(start = CoroutineStart.LAZY) { executions.incrementAndGet() })
            val heartbeat = requireNotNull(scope.tryLaunch(start = CoroutineStart.LAZY) { executions.incrementAndGet() })
            stream.attach(collector)
            stream.attachHeartbeat(heartbeat)
            stream.start()
            collector.start()
            heartbeat.start()
            runBlocking { withTimeout(5000) { collector.join(); heartbeat.join() } }
            assertTrue(collector.isCancelled)
            assertTrue(heartbeat.isCancelled)
            repeat(3) { stream.close() }
            assertEquals(1, ownerCloses.get())
            assertEquals(0, executions.get())
            assertEquals(0, count.get())
            assertFalse(stream.send("late"))
        } finally {
            stream.close()
            scope.close()
            runBlocking { withTimeout(5000) { scope.coroutineContext[Job]!!.join() } }
        }
    }

    @Test
    fun `already cancelled application closes a hub owner attached after writer cancellation`() {
        Fixture().use { fixture ->
            fixture.scope.close()
            fixture.open(awaitConnected = false)
            assertEquals(1, fixture.removals.get())
            fixture.assertNoWork()
        }
    }

    @Test
    fun `shared hub acceptance cannot recreate operations after connection close`() {
        Fixture().use { fixture ->
            val connection = fixture.transport.createHubConnection("ws-closed", ArcObservableHandshake(
                ArcPrincipal(), null, UUID.randomUUID(), emptyMap(), null
            ), { true }, {})
            connection.close()
            repeat(3) { revision ->
                assertEquals(HubSubscribeResult.UNAVAILABLE, fixture.transport.subscribe(
                    connection, "late", revision.toLong(), ObservableQuerySubscriptionRequest("lifecycle.observe")
                ))
            }
            assertEquals(0, connection.subscriptions.activeCount)
            assertEquals(0, connection.subscriptions.count)
            fixture.assertNoWork()
            fixture.assertAdmissionReturned()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["binding", "identity"])
    fun `transport shutdown during subscription preflight cannot admit work on a closed SSE hub`(phase: String) {
        Fixture(preflightArgument = true).use { fixture ->
            val hub = fixture.open()
            val callbacks = AtomicInteger()
            val close = { callbacks.incrementAndGet(); fixture.transport.close() }
            if (phase == "binding") fixture.onConversion = close else fixture.onIdentity = close
            assertEquals(404, fixture.subscribe(hub, withArgument = true).status)
            assertEquals(0, fixture.health.snapshot().totalSubscriptions)
            assertEquals(1, callbacks.get())
            fixture.joinChildren()
            fixture.assertNoWork()
            assertEquals(0, fixture.invocations.get())
            assertEquals(1, fixture.removals.get())
            assertEquals(404, fixture.subscribe(hub, withArgument = true).status)
            fixture.assertAdmissionReturned()
            assertNull(fixture.transport.tryReserveConnection())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["binding", "identity"])
    fun `reentrant preflight closure leaves no shared hub reservation`(phase: String) {
        Fixture(preflightArgument = true).use { fixture ->
            val connection = fixture.transport.createHubConnection("ws-preflight", ArcObservableHandshake(
                ArcPrincipal(), null, UUID.randomUUID(), emptyMap(), null
            ), { true }, {})
            try {
                val callbacks = AtomicInteger()
                val close = { callbacks.incrementAndGet(); connection.close() }
                if (phase == "binding") fixture.onConversion = close else fixture.onIdentity = close
                assertEquals(HubSubscribeResult.UNAVAILABLE, fixture.transport.subscribe(
                    connection, "quiet", 1, ObservableQuerySubscriptionRequest(
                        "lifecycle.observe", mapOf("id" to "05bac18d-3e07-4c42-9cb8-85cc341da007")
                    )
                ))
                assertEquals(1, callbacks.get())
                assertEquals(0, connection.subscriptions.activeCount)
                assertEquals(0, connection.subscriptions.count)
                fixture.joinChildren()
                fixture.assertNoWork()
                assertEquals(0, fixture.invocations.get())
                fixture.assertAdmissionReturned()
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun `closure before application health registration returns cannot leave late health state`() {
        Fixture().use { fixture ->
            val hub = fixture.open()
            fixture.beforeRegistered = { fixture.transport.close() }
            assertEquals(404, fixture.subscribe(hub).status)
            fixture.joinChildren()
            fixture.assertNoWork()
            assertEquals(0, fixture.invocations.get())
            assertEquals(1, fixture.removals.get())
            fixture.assertAdmissionReturned()
        }
    }

    @Test
    fun `replacement cancellation closing the hub cannot register replacement health or work`() {
        Fixture().use { fixture ->
            val connection = fixture.transport.createHubConnection("ws-replacement", ArcObservableHandshake(
                ArcPrincipal(), null, UUID.randomUUID(), emptyMap(), null
            ), { true }, {})
            val original = requireNotNull(connection.subscriptions.trySubscribe("quiet", 1))
            val job = requireNotNull(fixture.scope.tryLaunch(start = CoroutineStart.LAZY) {})
            original.attach(job)
            job.invokeOnCompletion { connection.close() }
            try {
                assertEquals(HubSubscribeResult.UNAVAILABLE, fixture.transport.subscribe(
                    connection, "quiet", 2, ObservableQuerySubscriptionRequest("lifecycle.observe")
                ))
                fixture.joinChildren()
                assertTrue(job.isCancelled)
                assertEquals(0, connection.subscriptions.activeCount)
                assertEquals(0, connection.subscriptions.count)
                fixture.assertNoWork()
                assertEquals(0, fixture.invocations.get())
                fixture.assertAdmissionReturned()
            } finally {
                connection.close()
            }
        }
    }

    private class Fixture(heartbeat: Boolean = false, preflightArgument: Boolean = false) : AutoCloseable {
        val scope = ArcApplicationCoroutineScope(1, 3)
        val health = DefaultQueryHealthTracker()
        val removals = AtomicInteger()
        val collections = AtomicInteger()
        val invocations = AtomicInteger()
        val quiet = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        var onPrincipal: () -> Unit = {}
        var onRegistered: () -> Unit = {}
        var beforeRegistered: () -> Unit = {}
        var onConversion: () -> Unit = {}
        var onIdentity: () -> Unit = {}
        private val hubs = mutableListOf<Hub>()
        private val mapper = (ArcObjectMapper.create() as JsonMapper).rebuild().addModule(
            SimpleModule().addSerializer(UUID::class.java, object : ValueSerializer<UUID>() {
                override fun serialize(value: UUID, generator: JsonGenerator, context: SerializationContext) {
                    onIdentity()
                    generator.writeString(value.toString())
                }
            })
        ).build()
        private val conversion = DefaultConversionService().apply {
            addConverter(String::class.java, UUID::class.java) { text ->
                onConversion()
                UUID.fromString(text)
            }
        }
        private val settings = ArcProperties().apply {
            observableQueries.keepAliveInterval = if (heartbeat) Duration.ofHours(1) else Duration.ZERO
            observableQueries.maximumConnections = 1
        }
        private val performer = object : QueryPerformer {
            override val fullyQualifiedName = FullyQualifiedQueryName("lifecycle.observe")
            override val descriptor = QueryDescriptor(
                "observe", "lifecycle", "kotlin.String", transport = QueryTransportType.OBSERVABLE,
                parameters = if (preflightArgument) listOf(ParameterDescriptor("id", "java.util.UUID")) else emptyList()
            )
            override suspend fun perform(context: QueryContext): Any {
                invocations.incrementAndGet()
                return flow {
                    collections.incrementAndGet()
                    try {
                        emit("only value")
                        quiet.countDown()
                        awaitCancellation()
                    } finally {
                        cancelled.countDown()
                    }
                }
            }
        }
        private val registry = ConcurrentQueryPerformerRegistry().apply { register(performer) }
        private val tracker = object : QueryHealthTracker by health {
            override fun registerSubscription(connectionId: String, protocol: String, metadata: QuerySubscriptionMetadata) {
                beforeRegistered()
                health.registerSubscription(connectionId, protocol, metadata)
                onRegistered()
            }
            override fun removeConnection(connectionId: String) {
                removals.incrementAndGet()
                health.removeConnection(connectionId)
            }
        }
        val transport = ArcObservableQueryTransport(
            registry, DefaultObservableQueryPipeline(registry), mock(ServiceResolver::class.java),
            ArcQueryRequestBinder(mapper, conversion, javaClass.classLoader), mapper, scope, settings, true,
            ArcPrincipalFactory { _, _ -> onPrincipal(); ArcPrincipal() },
            ArcTenantResolutionService(TenantIdResolver { null }, TenantAccessEvaluator { _, _ -> true }, settings), tracker
        )

        fun open(awaitConnected: Boolean = true): Hub {
            val output = Output()
            val response = object : MockHttpServletResponse() {
                override fun getOutputStream(): ServletOutputStream = output
            }
            val request = MockHttpServletRequest("GET", OBSERVABLE_QUERY_SSE_ROUTE).apply { isAsyncSupported = true }
            transport.sseConnectHandler().handleRequest(request, response)
            val async = request.asyncContext as MockAsyncContext
            val id = if (awaitConnected) {
                mapper.readTree(output.frames.poll(5, TimeUnit.SECONDS)!!.removePrefix("data: ").trim()).path("payload").stringValue()
            } else "closed-before-connected"
            return Hub(id, async, output).also(hubs::add)
        }

        fun subscribe(hub: Hub, withArgument: Boolean = false): MockHttpServletResponse {
            val arguments = if (withArgument) """, "arguments":{"id":"05bac18d-3e07-4c42-9cb8-85cc341da007"}""" else ""
            val request = MockHttpServletRequest("POST", OBSERVABLE_QUERY_SSE_SUBSCRIBE_ROUTE).apply {
                contentType = "application/json"
                setContent("""{"connectionId":"${hub.id}","queryId":"quiet","revision":1,"request":{"queryName":"lifecycle.observe","transferMode":"full"$arguments}}""".toByteArray())
            }
            return MockHttpServletResponse().also { transport.sseSubscribeHandler().handleRequest(request, it) }
        }

        fun activate(hub: Hub, awaitFrame: Boolean = true) {
            assertEquals(200, subscribe(hub).status)
            assertTrue(quiet.await(5, TimeUnit.SECONDS), "upstream did not emit and enter cancellation wait")
            if (awaitFrame) assertTrue(requireNotNull(hub.output.frames.poll(5, TimeUnit.SECONDS)).contains("QueryResult"))
            assertEquals(1, collections.get())
            assertEquals(1, health.snapshot().totalConnections)
            assertEquals(1, health.snapshot().totalSubscriptions)
            assertEquals(1, transport.activeConnectionCount)
        }

        fun end(hub: Hub, terminal: String) {
            val event = AsyncEvent(hub.async, IOException("disconnected"))
            hub.async.listeners.toList().forEach { listener ->
                when (terminal) {
                    "timeout" -> listener.onTimeout(event)
                    "error" -> listener.onError(event)
                    "completion" -> listener.onComplete(event)
                }
            }
        }

        fun assertCancelled(hub: Hub) {
            assertTrue(cancelled.await(5, TimeUnit.SECONDS), "quiet upstream finally was not invoked")
            runBlocking { withTimeout(5000) { scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } } }
            assertEquals(0, health.snapshot().totalConnections)
            assertEquals(0, health.snapshot().totalSubscriptions)
            assertEquals(0, transport.activeConnectionCount)
            assertEquals(404, subscribe(hub).status)
            assertEquals(1, collections.get())
        }

        fun joinChildren() {
            runBlocking { withTimeout(5000) { scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } } }
        }

        fun assertNoWork() {
            assertEquals(0, collections.get())
            assertEquals(0, health.snapshot().totalSubscriptions)
            assertEquals(0, health.snapshot().totalConnections)
            assertEquals(0, transport.activeConnectionCount)
        }

        fun reserveAllAdmission(): List<Job> = (1..4).map { requireNotNull(scope.tryLaunch(start = CoroutineStart.LAZY) {}) }

        fun assertAdmissionReturned() {
            val reservations = reserveAllAdmission()
            try {
                assertNull(scope.tryLaunch(start = CoroutineStart.LAZY) {})
                assertTrue(reservations.all { !it.isActive && !it.isCompleted })
            } finally {
                reservations.forEach(Job::cancel)
                runBlocking { reservations.forEach { it.join() } }
            }
        }

        fun assertCapacityReturned() {
            assertAdmissionReturned()
            val lease = transport.tryReserveConnection()
            assertNotNull(lease)
            assertNull(transport.tryReserveConnection())
            lease!!.close()
            lease.close()
            assertEquals(0, transport.activeConnectionCount)
        }

        override fun close() {
            hubs.forEach { it.output.releaseQueryWrite.countDown() }
            transport.close()
            scope.close()
            runBlocking { withTimeout(5000) { scope.coroutineContext[Job]!!.join() } }
        }
    }

    private data class Hub(val id: String, val async: MockAsyncContext, val output: Output)

    private class Output : ServletOutputStream() {
        val frames = LinkedBlockingQueue<String>()
        val queryWriteEntered = CountDownLatch(1)
        val releaseQueryWrite = CountDownLatch(1)
        @Volatile var failQueryWrite = false
        override fun isReady(): Boolean = true
        override fun setWriteListener(listener: WriteListener) = error("Blocking SSE writer does not use WriteListener")
        override fun write(value: Int) = error("SSE frames must be written as bytes")
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val frame = String(bytes, offset, length, Charsets.UTF_8)
            if (failQueryWrite && frame.contains("QueryResult")) {
                queryWriteEntered.countDown()
                check(releaseQueryWrite.await(5, TimeUnit.SECONDS))
                throw IOException("socket write failed")
            }
            frames.add(frame)
        }
    }
}
