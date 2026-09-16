// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.concepts.ArcEnum
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.QueryDescriptor
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ObservableQueryEnumArgumentTest {
    @Test
    fun `ordinary Kotlin enum arguments reach both guards on both query emissions`(): Unit = runBlocking {
        assertGuardedQuery(Plain.FIRST)
    }

    @Test
    fun `ArcEnum integer wire values and constant identity survive guarded queries`(): Unit = runBlocking {
        val mapper = ArcObjectMapper.create()
        assertEquals("17", mapper.writeValueAsString(Wire.FIRST))
        assertSame(Wire.FIRST, mapper.readValue("17", Wire::class.java))
        assertGuardedQuery(Wire.FIRST)
        assertEquals(17, Wire.FIRST.value())
    }

    @Test
    fun `checked enum subclass state and enum array classes survive independent dispatch copies`(): Unit = runBlocking {
        assertFalse(ScalarState.FIRST.javaClass.isEnum)
        val subclassArray = java.lang.reflect.Array.newInstance(ScalarState.FIRST.javaClass, 1)
        java.lang.reflect.Array.set(subclassArray, 0, ScalarState.FIRST)
        val plain = arrayOf(Plain.FIRST)
        val arrays = listOf(plain, arrayOf(Wire.FIRST), arrayOf(ScalarState.FIRST), subclassArray, arrayOf<Enum<*>>(Plain.FIRST))
        val seenArrays = mutableListOf<Any>()
        var expected = Plain.FIRST
        var calls = 0
        val guards = DefaultObservableQueryEmissionGuards(List(2) { index -> BlockingObservableQueryEmissionGuard { ctx ->
            val copies = ctx.arguments["value"] as List<*>
            arrays.zip(copies).forEach { (original, copied) ->
                requireNotNull(copied)
                assertEquals(original.javaClass, copied.javaClass)
                assertNotSame(original, copied)
                assertSame(java.lang.reflect.Array.get(original, 0), java.lang.reflect.Array.get(copied, 0))
                seenArrays.forEach { assertNotSame(it, copied) }
                seenArrays.add(copied)
            }
            assertSame(expected, (copies[0] as Array<*>).single())
            if (index == 0) java.lang.reflect.Array.set(copies[0], 0, Plain.SECOND)
            assertSame(ScalarState.FIRST, java.lang.reflect.Array.get(copies[3], 0))
            assertEquals("first", ScalarState.FIRST.label())
            calls++
            ObservableQueryEmissionVerdict.ALLOW
        } })
        val original = context(arrays)
        assertEquals(ObservableQueryEmissionVerdict.ALLOW, guards.guard(original))
        assertSame(Plain.FIRST, plain.single())
        plain[0] = Plain.SECOND
        expected = Plain.SECOND
        assertEquals(ObservableQueryEmissionVerdict.ALLOW, guards.guard(original))
        assertEquals(4, calls)
        assertSame(Plain.SECOND, plain.single())
    }

    @Test
    fun `mutable or unknown enum instance state denies before every guard including subclass and inherited fields`(): Unit = runBlocking {
        val unsafe = listOf(
            MutableField.VALUE, MutableReference.VALUE, ArrayReference.VALUE, UnknownReference.VALUE,
            NonfinalScalarType.VALUE, MutableSubclass.VALUE, MutableParent.VALUE, EnumReference.VALUE
        )
        unsafe.forEach { value ->
            var calls = 0
            val guards = DefaultObservableQueryEmissionGuards(List(2) { BlockingObservableQueryEmissionGuard {
                calls++; ObservableQueryEmissionVerdict.ALLOW
            } })
            // A safe first argument must not allow guard one to run before an unsafe later argument is checked.
            val ctx = context(listOf(Plain.FIRST, value))
            assertEquals(ObservableQueryEmissionVerdict.DENY_AND_TERMINATE, guards.guard(ctx), value.javaClass.name)
            assertEquals(0, calls)
            assertEquals(ObservableQueryEmissionVerdict.ALLOW, DefaultObservableQueryEmissionGuards().guard(ctx))
        }
        assertEquals(0, MutableField.VALUE.state)
        assertEquals(listOf("original"), MutableReference.VALUE.state)
        assertEquals(0, MutableSubclass.VALUE.state())
        assertEquals(0, MutableParent.VALUE.state)
    }

    private suspend fun assertGuardedQuery(value: Enum<*>) {
        val original = context(value)
        var calls = 0
        val guards = DefaultObservableQueryEmissionGuards(List(2) { BlockingObservableQueryEmissionGuard {
            assertSame(value, it.arguments["value"])
            assertEquals(value.javaClass, it.arguments["value"]!!.javaClass)
            calls++
            ObservableQueryEmissionVerdict.ALLOW
        } })
        val registry = ConcurrentQueryPerformerRegistry().apply { register(object : QueryPerformer {
            override val fullyQualifiedName = original.queryName
            override val descriptor = QueryDescriptor("observe", "Tests", String::class.java.name, transport = QueryTransportType.OBSERVABLE)
            override suspend fun perform(context: QueryContext): Any {
                assertSame(value, context.request.arguments["value"])
                return flowOf("one", "two")
            }
        }) }
        val stream = DefaultObservableQueryPipeline(registry, emissionGuards = guards).open(
            QueryRequest(original.queryName, original.arguments),
            QueryExecutionOptions(original.correlationId, original.principal, original.serviceResolver)
        ) as ObservableQueryOpenResult.Stream
        val results = stream.results.toList()
        assertTrue(results.all { it.isSuccess })
        assertEquals(listOf("one", "two"), results.map { it.data })
        assertEquals(4, calls)
    }

    private fun context(value: Any) = ObservableQueryEmissionContext(
        FullyQualifiedQueryName("Tests.observe"), mapOf("value" to value), ArcPrincipal.anonymous(), null, null, UUID.randomUUID(),
        object : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null }, true, null
    )

    private enum class Plain { FIRST, SECOND }
    internal enum class Wire(private val wire: Int) : ArcEnum { FIRST(17); override fun value(): Int = wire }
    private enum class ScalarState(val id: UUID, val count: Int, val date: java.time.LocalDate, val optional: String?) {
        FIRST(UUID.fromString("00000000-0000-0000-0000-000000000001"), 1, java.time.LocalDate.of(2026, 1, 1), null) {
            private val text = "first"
            override fun label(): String = text
        };
        abstract fun label(): String
    }
    private enum class MutableField { VALUE; var state = 0 }
    private enum class MutableReference { VALUE; val state = mutableListOf("original") }
    private enum class ArrayReference { VALUE; val state = intArrayOf(1) }
    private enum class UnknownReference { VALUE; val state: Number = 1 }
    private enum class NonfinalScalarType { VALUE; val state = java.math.BigDecimal.ONE }
    private enum class EnumReference { VALUE; val state = Plain.FIRST }
    private enum class MutableSubclass {
        VALUE { private var count = 0; override fun state(): Int = count };
        abstract fun state(): Int
    }
    private enum class MutableParent {
        VALUE { override fun label(): String = "value" };
        var state = 0
        abstract fun label(): String
    }
}
