// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.metadata.QueryDescriptor
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ObservableQueryArgumentIsolationTest {
    @Test
    fun `two guards and two dispatches isolate nested values and preserve exact array classes`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val ints = intArrayOf(1)
        val boxed = arrayOf(2L)
        val ids = arrayOf(id)
        val concept = MutableId(id)
        val nested = arrayListOf<Any?>(linkedMapOf("ints" to ints, "boxed" to boxed, "ids" to ids, "concept" to concept))
        val arguments = linkedMapOf("nested" to nested, "null" to null)
        val seen = mutableListOf<ObservableQueryEmissionContext>()
        val guards = DefaultObservableQueryEmissionGuards(listOf(
            BlockingObservableQueryEmissionGuard { context ->
                seen.add(context)
                val map = (context.arguments["nested"] as List<*>).single() as Map<*, *>
                (map["ints"] as IntArray)[0] = 99
                java.lang.reflect.Array.set(map["boxed"], 0, 99L)
                java.lang.reflect.Array.set(map["ids"], 0, UUID.randomUUID())
                (map["concept"] as MutableId).value = UUID.randomUUID()
                (map as MutableMap<*, *>).clear()
                (context.arguments["nested"] as MutableList<*>).clear()
                ObservableQueryEmissionVerdict.ALLOW
            },
            BlockingObservableQueryEmissionGuard { context ->
                seen.add(context)
                val map = (context.arguments["nested"] as List<*>).single() as Map<*, *>
                assertEquals(1, (map["ints"] as IntArray).single())
                assertEquals(boxed.javaClass, map["boxed"]!!.javaClass)
                assertEquals(ids.javaClass, map["ids"]!!.javaClass)
                assertEquals(2L, (map["boxed"] as Array<*>).single())
                assertEquals(id, (map["ids"] as Array<*>).single())
                assertEquals(id, (map["concept"] as MutableId).value())
                assertNotSame(concept, map["concept"])
                assertTrue(context.arguments.containsKey("null"))
                assertFalse(context.arguments.containsKey("omitted"))
                ObservableQueryEmissionVerdict.ALLOW
            }
        ))
        val original = context(arguments)
        repeat(2) { assertEquals(ObservableQueryEmissionVerdict.ALLOW, guards.guard(original)) }
        assertEquals(4, seen.size)
        assertEquals(1, ints.single())
        assertEquals(2L, boxed.single())
        assertEquals(id, ids.single())
        assertEquals(id, concept.value())
        assertEquals(1, nested.size)
        assertEquals(listOf("ints", "boxed", "ids", "concept"), (nested.single() as Map<*, *>).keys.toList())
        seen.forEach { assertSame(original.data, it.data); assertSame(original.principal, it.principal) }
        assertNotSame(seen[0].arguments["nested"], seen[1].arguments["nested"])
        assertNotSame(seen[0].arguments["nested"], seen[2].arguments["nested"])
    }

    @Test
    fun `caller changes while guard one awaits affect only the next dispatch`(): Unit = runBlocking {
        withTimeout(5_000) {
            val values = intArrayOf(1)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableFuture<ObservableQueryEmissionVerdict>()
            val seen = mutableListOf<Int>()
            val guards = DefaultObservableQueryEmissionGuards(listOf(
                GuardObservableQueryEmission { entered.complete(Unit); release },
                BlockingObservableQueryEmissionGuard { seen.add((it.arguments["value"] as IntArray).single()); ObservableQueryEmissionVerdict.ALLOW }
            ))
            val original = context(mapOf("value" to values))
            val first = async { guards.guard(original) }
            entered.await()
            values[0] = 2
            release.complete(ObservableQueryEmissionVerdict.ALLOW)
            assertEquals(ObservableQueryEmissionVerdict.ALLOW, first.await())
            assertEquals(ObservableQueryEmissionVerdict.ALLOW, guards.guard(original))
            assertEquals(listOf(1, 2), seen)
        }
    }

    @Test
    fun `unsupported graphs and cycles deny before invoking any guard but no guards allow`(): Unit = runBlocking {
        val cycle = arrayListOf<Any?>().also { it.add(it) }
        val cases = listOf(Any(), cycle, mapOf(1 to "not a string key"), MutableEnum.VALUE, ExtraState("id"))
        cases.forEach { value ->
            var calls = 0
            val guards = DefaultObservableQueryEmissionGuards(listOf(BlockingObservableQueryEmissionGuard { calls++; ObservableQueryEmissionVerdict.ALLOW }))
            assertEquals(ObservableQueryEmissionVerdict.DENY_AND_TERMINATE, guards.guard(context(mapOf("value" to value))))
            assertEquals(0, calls)
            assertEquals(ObservableQueryEmissionVerdict.ALLOW, DefaultObservableQueryEmissionGuards().guard(context(mapOf("value" to value))))
        }
    }

    @Test
    fun `snapshot then results capture per dispatch without freezing performer or result data`(): Unit = runBlocking {
        val original = intArrayOf(1)
        val data = MutableId(UUID.randomUUID())
        val state = MutableStateFlow(data)
        val name = FullyQualifiedQueryName("Tests.observe")
        val registry = ConcurrentQueryPerformerRegistry().apply { register(object : QueryPerformer {
            override val fullyQualifiedName = name
            override val descriptor = QueryDescriptor("observe", "Tests", MutableId::class.java.name, transport = QueryTransportType.OBSERVABLE)
            override suspend fun perform(context: QueryContext): Any {
                assertSame(original, context.request.arguments["value"])
                original[0] = 2
                return state
            }
        }) }
        val observations = mutableListOf<Int>()
        val firstFlags = mutableListOf<Boolean>()
        val guards = DefaultObservableQueryEmissionGuards(listOf(
            BlockingObservableQueryEmissionGuard {
                (it.arguments["value"] as IntArray)[0] = 99
                assertSame(data, it.data)
                ObservableQueryEmissionVerdict.ALLOW
            },
            BlockingObservableQueryEmissionGuard {
                observations.add((it.arguments["value"] as IntArray).single())
                firstFlags.add(it.isFirstEmission)
                ObservableQueryEmissionVerdict.ALLOW
            }
        ))
        val ctx = context(emptyMap())
        val stream = DefaultObservableQueryPipeline(registry, emissionGuards = guards).open(
            QueryRequest(name, mapOf("value" to original)), QueryExecutionOptions(ctx.correlationId, ctx.principal, ctx.serviceResolver)
        ) as ObservableQueryOpenResult.Stream
        assertEquals(2, original.single())
        original[0] = 3
        assertSame(data, stream.snapshot!!.toList().single().data)
        original[0] = 4
        assertSame(data, stream.results.take(1).toList().single().data)
        assertEquals(listOf(3, 4), observations)
        assertEquals(listOf(true, true), firstFlags)
        assertEquals(4, original.single())
    }

    private fun context(arguments: Map<String, Any?>) = ObservableQueryEmissionContext(
        FullyQualifiedQueryName("Tests.observe"), arguments, ArcPrincipal.anonymous(), "tenant", "namespace", UUID.randomUUID(),
        object : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null }, true, Any()
    )

    internal class MutableId(var value: UUID) : ConceptAs<UUID> { override fun value(): UUID = value }
    private class ExtraState(private val value: String) : ConceptAs<String> {
        val hidden = mutableListOf("state")
        override fun value(): String = value
    }
    private enum class MutableEnum { VALUE; var state = 0 }
}
