// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.results.QueryResult
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ObservableQueryCoreTest {
    @Test
    fun `protocol enums use exact strings and defaults are omitted`() {
        val mapper = ArcObjectMapper.create()
        val json = mapper.writeValueAsString(ObservableQueryHubMessage(ObservableQueryHubMessageType.Subscribe))
        assertEquals("{\"type\":\"Subscribe\"}", json)
        assertEquals("\"delta\"", mapper.writeValueAsString(ObservableQueryTransferMode.DELTA))
        assertEquals("0", mapper.writeValueAsString(QueryTransportType.REQUEST_RESPONSE))
    }

    @Test
    fun `full and delta streams wrap emissions deterministically`() = runBlocking {
        val registry = ConcurrentQueryPerformerRegistry()
        registry.register(performer(flowOf(listOf(Item(1, "one")), listOf(Item(1, "two"), Item(2, "two")))))
        val pipeline = DefaultObservableQueryPipeline(registry)
        val opened = pipeline.open(request(), options(), ObservableQueryTransferMode.DELTA)
        val emissions = (opened as ObservableQueryOpenResult.Stream).results.toList()

        assertEquals(listOf(Item(1, "one")), emissions[0].data)
        assertNull(emissions[0].changeSet)
        assertNull(emissions[1].data)
        assertEquals(listOf(Item(2, "two")), emissions[1].changeSet!!.added)
        assertEquals(listOf(Item(1, "two")), emissions[1].changeSet!!.replaced)
    }

    @Test
    fun `missing stable identity falls back to full snapshot`() = runBlocking {
        val registry = ConcurrentQueryPerformerRegistry()
        registry.register(performer(flowOf(listOf(NoId("one")), listOf(NoId("two")))))
        val opened = DefaultObservableQueryPipeline(registry).open(request(), options(), ObservableQueryTransferMode.DELTA)
        val emissions = (opened as ObservableQueryOpenResult.Stream).results.toList()
        assertEquals(listOf(NoId("two")), emissions[1].data)
        assertNull(emissions[1].changeSet)
    }

    @Test
    fun `opening runs rejection filter once`() = runBlocking {
        var calls = 0
        val registry = ConcurrentQueryPerformerRegistry()
        registry.register(performer(flowOf("ignored")))
        val pipeline = DefaultObservableQueryPipeline(registry, listOf(QueryFilter {
            calls++
            QueryResult.unauthorized<Any?>(it.correlationId)
        }))
        val opened = pipeline.open(request(), options())
        assertTrue(opened is ObservableQueryOpenResult.Failure)
        assertFalse((opened as ObservableQueryOpenResult.Failure).result.isAuthorized)
        assertEquals(1, calls)
    }

    @Test
    fun `collector cancellation cancels upstream`() = runBlocking {
        val cancelled = AtomicBoolean()
        val upstream = flow {
            try {
                emit("started")
                kotlinx.coroutines.awaitCancellation()
            } finally {
                cancelled.set(true)
            }
        }
        val registry = ConcurrentQueryPerformerRegistry()
        registry.register(performer(upstream))
        val opened = DefaultObservableQueryPipeline(registry).open(request(), options()) as ObservableQueryOpenResult.Stream
        val job = launch { opened.results.collect {} }
        yield()
        job.cancelAndJoin()
        assertTrue(cancelled.get())
    }

    private fun performer(data: Flow<*>): QueryPerformer = object : QueryPerformer {
        override val descriptor = QueryDescriptor(
            "observe",
            "Tests.Model",
            "Tests.Model",
            transport = QueryTransportType.OBSERVABLE,
            isEnumerable = true
        )
        override val fullyQualifiedName = FullyQualifiedQueryName("Tests.Model.observe")
        override suspend fun perform(context: QueryContext): Any = data
    }

    private fun request() = QueryRequest(FullyQualifiedQueryName("Tests.Model.observe"))
    private fun options() = QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), EmptyServices)

    private data class Item(val id: Int, val value: String)
    private data class NoId(val value: String)

    private object EmptyServices : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
}

internal class ObservableQueryRevisionStateTest {
    @Test
    fun `revisions tombstones races and cap follow strict ordering`() {
        var now = Instant.parse("2025-01-01T00:00:00Z")
        val states = ObservableQuerySubscriptionStates({ now })
        val identity = ObservableQuerySubscriptionIdentity(
            FullyQualifiedQueryName("Tests.Query"),
            linkedMapOf("value" to "captured", "explicitNull" to null),
            ArcPrincipal("name", true),
            "tenant",
            "namespace",
            UUID.randomUUID()
        )
        val first = states.trySubscribe("query", 1, identity)!!
        assertNull(states.trySubscribe("query", 1, identity))
        assertTrue(states.tryUnsubscribe("query", 1))
        assertTrue(first.isCancelled)
        assertTrue(states.tryUnsubscribe("query", 1))
        assertNull(states.trySubscribe("query", null, identity))
        assertFalse(states.tryUnsubscribe("query", null))
        assertNull(states.trySubscribe("query", 1, identity))
        assertTrue(states.trySubscribe("query", 2, identity) != null)
        assertTrue(states.tryUnsubscribe("query", 2))

        repeat(1_100) { states.tryUnsubscribe("tombstone-$it", 1) }
        assertTrue(states.count <= ObservableQuerySubscriptionStates.MAXIMUM_RETAINED_TOMBSTONES + 1)
        now = now.plusSeconds(121)
        states.cleanup()
        assertEquals(0, states.count)
        assertEquals("captured", identity.createArguments()["value"])
        assertTrue(identity.createArguments().containsKey("explicitNull"))
        assertNull(identity.createArguments()["explicitNull"])
    }
}
