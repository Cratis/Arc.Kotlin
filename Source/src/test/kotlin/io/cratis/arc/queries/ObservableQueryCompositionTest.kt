// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.results.QueryResult
import java.util.UUID
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ObservableQueryCompositionTest {
    @Test
    fun `explicit null preserves first delivery and intercepted baseline through suppression`() = runBlocking {
        suppressed(null)
    }

    @Test
    fun `explicit delta preserves first delivery and intercepted baseline through suppression`() = runBlocking {
        suppressed(ObservableQueryTransferMode.DELTA)
    }

    @Test
    fun `explicit full preserves first delivery through suppression without computed changes`() = runBlocking {
        suppressed(ObservableQueryTransferMode.FULL)
    }

    @Test
    fun `omitting Kotlin mode argument is full even through suppression`() = runBlocking {
        suppressed(ObservableQueryTransferMode.FULL, shortCall = true)
    }

    @Test
    fun `explicit null snapshot and stream establish independent intercepted baselines`() = runBlocking {
        independent(null)
    }

    @Test
    fun `explicit delta snapshot and stream establish independent intercepted baselines`() = runBlocking {
        independent(ObservableQueryTransferMode.DELTA)
    }

    @Test
    fun `explicit full snapshot and stream each announce first delivery`() = runBlocking {
        independent(ObservableQueryTransferMode.FULL)
    }

    @Test
    fun `omitting Kotlin mode argument gives full snapshot and subsequent stream results`() = runBlocking {
        independent(ObservableQueryTransferMode.FULL, shortCall = true)
    }

    private suspend fun suppressed(mode: ObservableQueryTransferMode?, shortCall: Boolean = false) = coroutineScope {
        val fixture = Fixture("S", setOf("S", "B"))
        val opened = fixture.open(mode, shortCall)
        // toList waits for normal completion, including the pipeline's post-emission bookkeeping.
        val snapshot = withTimeout(5_000) {
            requireNotNull(opened.snapshot).onEach { fixture.events.send("snapshot:next") }.toList()
        }
        fixture.events.send("snapshot:complete")
        fixture.processed("S", true)
        assertEquals(emptyList<QueryResult<*>>(), snapshot)
        fixture.event("snapshot:complete")
        val delivered = Channel<QueryResult<*>>(Channel.UNLIMITED)
        val collector = launch {
            opened.results.collect {
                fixture.events.send("results:next")
                delivered.send(it)
            }
        }
        try {
            fixture.processed("S", true)
            fixture.state.value = rows("A")
            fixture.processed("A", true)
            fixture.assertEnvelope(withTimeout(5_000) { delivered.receive() }, "A", null, mode)
            fixture.event("results:next")
            fixture.state.value = rows("B")
            fixture.processed("B", false)
            fixture.state.value = rows("C")
            fixture.processed("C", false)
            fixture.assertEnvelope(withTimeout(5_000) { delivered.receive() }, "C", "A", mode)
            fixture.event("results:next")
            assertEquals(listOf(true, true, true, false, false), fixture.flags)
            assertTrue(delivered.tryReceive().isFailure, "suppressed values must never be delivered")
        } finally {
            collector.cancelAndJoin()
            delivered.close()
        }
        assertEquals(0, fixture.state.subscriptionCount.value)
        assertTrue(fixture.events.tryReceive().isFailure)
    }

    private suspend fun independent(mode: ObservableQueryTransferMode?, shortCall: Boolean = false) = coroutineScope {
        val fixture = Fixture("A", emptySet())
        val opened = fixture.open(mode, shortCall)
        val snapshot = withTimeout(5_000) {
            requireNotNull(opened.snapshot).onEach { fixture.events.send("snapshot:next") }.toList()
        }
        fixture.events.send("snapshot:complete")
        fixture.processed("A", true)
        assertEquals(1, snapshot.size)
        fixture.assertEnvelope(snapshot.single(), "A", null, mode)
        fixture.event("snapshot:next")
        fixture.event("snapshot:complete")
        // A completed snapshot must not prime B's first-delivery flag or change-set baseline.
        fixture.state.value = rows("B")
        val delivered = Channel<QueryResult<*>>(Channel.UNLIMITED)
        val collector = launch {
            opened.results.collect {
                fixture.events.send("results:next")
                delivered.send(it)
            }
        }
        try {
            fixture.processed("B", true)
            fixture.assertEnvelope(withTimeout(5_000) { delivered.receive() }, "B", null, mode)
            fixture.event("results:next")
            fixture.state.value = rows("C")
            fixture.processed("C", false)
            fixture.assertEnvelope(withTimeout(5_000) { delivered.receive() }, "C", "B", mode)
            fixture.event("results:next")
            assertEquals(listOf(true, true, false), fixture.flags)
            assertTrue(delivered.tryReceive().isFailure)
        } finally {
            collector.cancelAndJoin()
            delivered.close()
        }
        assertEquals(0, fixture.state.subscriptionCount.value)
        assertTrue(fixture.events.tryReceive().isFailure)
    }

    private class Fixture(initial: String, suppressed: Set<String>) {
        val state = MutableStateFlow(rows(initial))
        val events = Channel<String>(Channel.UNLIMITED)
        val flags = mutableListOf<Boolean>()
        val name = FullyQualifiedQueryName("Composition.observe")
        val request = QueryRequest(name, mapOf("argument" to "captured", "explicitNull" to null))
        val principal = ArcPrincipal("Ada", true, setOf("operator"))
        val options = QueryExecutionOptions(UUID.randomUUID(), principal, Services, "tenant", "namespace")
        private val renderer = object : BlockingQueryRendererFor<Any> {
            override fun queryType(): Class<Any> = Any::class.java
            override fun renderBlocking(query: Any, current: QueryRendererResult, context: QueryContext): QueryRendererResult {
                assertContext(context)
                val values = (query as List<*>).map { it as Item }
                assertEquals(rows(values.first().value), values)
                events.trySend("render:${values.first().value}").getOrThrow()
                return current
            }
        }
        private val interceptor = object : BlockingReadModelInterceptor<Item> {
            override fun readModelType(): Class<Item> = Item::class.java
            override fun interceptBlocking(readModel: Item, context: QueryContext): Item {
                assertContext(context)
                events.trySend("intercept:${readModel.value}:${readModel.key}").getOrThrow()
                return readModel.copy(value = readModel.value + "!")
            }
        }
        private val guard = BlockingObservableQueryEmissionGuard { context ->
            assertEquals(name, context.queryName)
            assertEquals(request.arguments, context.arguments)
            assertSame(principal, context.principal)
            assertEquals(options.correlationId, context.correlationId)
            assertEquals("tenant", context.tenantId)
            assertEquals("namespace", context.tenantNamespace)
            assertSame(Services, context.serviceResolver)
            val values = (context.data as List<*>).map { it as Item }
            val step = values.first().value.removeSuffix("!")
            assertEquals(intercepted(step), values)
            flags.add(context.isFirstEmission)
            events.trySend("guard:$step:${context.isFirstEmission}").getOrThrow()
            if (step in suppressed) ObservableQueryEmissionVerdict.SUPPRESS else ObservableQueryEmissionVerdict.ALLOW
        }
        private val registry = ConcurrentQueryPerformerRegistry().apply {
            register(object : QueryPerformer {
                override val descriptor = QueryDescriptor(
                    "observe", "Composition", Item::class.java.name,
                    transport = QueryTransportType.OBSERVABLE, isEnumerable = true
                )
                override val fullyQualifiedName = name
                override suspend fun perform(context: QueryContext): Any {
                    assertContext(context)
                    return state
                }
            })
        }
        private val pipeline = DefaultObservableQueryPipeline(
            registry, changeSets = ChangeSetComputer(), renderers = DefaultQueryRenderers(listOf(renderer)),
            readModelInterceptors = DefaultReadModelInterceptors(listOf(interceptor)),
            emissionGuards = DefaultObservableQueryEmissionGuards(listOf(guard))
        )

        suspend fun open(mode: ObservableQueryTransferMode?, shortCall: Boolean): ObservableQueryOpenResult.Stream =
            (if (shortCall) pipeline.open(request, options) else pipeline.open(request, options, mode) {
                (it as Item).key
            }) as ObservableQueryOpenResult.Stream

        fun assertContext(context: QueryContext) {
            assertSame(request, context.request)
            assertEquals(request.arguments, context.request.arguments)
            assertEquals(name, context.queryName)
            assertSame(principal, context.principal)
            assertSame(Services, context.serviceResolver)
            assertEquals(options.correlationId, context.correlationId)
            assertEquals("tenant", context.tenantId)
            assertEquals("namespace", context.tenantNamespace)
        }

        suspend fun event(expected: String) {
            assertEquals(expected, withTimeout(5_000) { events.receive() })
        }

        suspend fun processed(step: String, first: Boolean) {
            event("render:$step")
            rows(step).forEach { event("intercept:$step:${it.key}") }
            event("guard:$step:$first")
        }

        fun assertEnvelope(result: QueryResult<*>, step: String, previous: String?, mode: ObservableQueryTransferMode?) {
            assertTrue(result.isSuccess)
            assertTrue(result.isReady)
            assertEquals(options.correlationId, result.correlationId)
            val delta = mode == ObservableQueryTransferMode.DELTA && previous != null
            assertEquals(if (delta) null else intercepted(step), result.data)
            if (mode == ObservableQueryTransferMode.FULL || (mode == ObservableQueryTransferMode.DELTA && previous == null)) {
                assertNull(result.changeSet)
            } else {
                val changes = requireNotNull(result.changeSet)
                if (previous == null) {
                    assertEquals(intercepted(step), changes.added, "first delivery starts without a baseline")
                    assertEquals(emptyList<Item>(), changes.replaced)
                    assertEquals(emptyList<Item>(), changes.removed)
                } else {
                    // Explicit expected identities distinguish the last delivered A or B from every suppressed value.
                    assertEquals(listOf(Item(4, "C!")), changes.added)
                    assertEquals(listOf(Item(1, "C!")), changes.replaced)
                    assertEquals(listOf(Item(if (previous == "A") 2 else 3, "$previous!")), changes.removed)
                }
            }
        }
    }

    private data class Item(val key: Int, val value: String)

    private object Services : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }

    private companion object {
        fun rows(step: String): List<Item> = when (step) {
            "S" -> listOf(Item(9, "S"))
            "A" -> listOf(Item(1, "A"), Item(2, "A"))
            "B" -> listOf(Item(1, "B"), Item(3, "B"))
            "C" -> listOf(Item(1, "C"), Item(4, "C"))
            else -> error("Unknown step $step")
        }
        fun intercepted(step: String): List<Item> = rows(step).map { it.copy(value = it.value + "!") }
    }
}
