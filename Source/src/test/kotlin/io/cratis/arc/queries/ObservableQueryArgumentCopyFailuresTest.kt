// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.json.ArcObjectMapper
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule

internal class ObservableQueryArgumentCopyFailuresTest {
    @Test
    fun `configured scalar codec must reconstruct fresh instances and preserve scalar value`(): Unit = runBlocking {
        val original = Id("one")
        var reads = 0
        val good = mapper({ Id(it.removePrefix("wire:")).also { reads++ } })
        val seen = mutableListOf<Any?>()
        val guards = DefaultObservableQueryEmissionGuards(List(2) { BlockingObservableQueryEmissionGuard {
            seen.add(it.arguments["value"])
            ObservableQueryEmissionVerdict.ALLOW
        } }, good)
        assertEquals(ObservableQueryEmissionVerdict.ALLOW, guards.guard(context(original)))
        assertEquals(3, reads, "One preflight probe plus two independent guard reconstructions")
        assertEquals(listOf("one", "one"), seen.map { (it as Id).value() })
        assertNotSame(original, seen[0])
        assertNotSame(seen[0], seen[1])

        val cached = Id("one")
        listOf(
            mapper({ original }), mapper({ cached }), mapper({ Id("changed") }),
            mapper({ error("cannot decode") }), mapper({ Id("one") }, { _, _ -> error("cannot encode") }),
            mapper({ Id("one") }, { _, generator -> generator.writeStartObject(); generator.writeEndObject() })
        ).forEach { bad -> assertDeniedBeforeGuard(original, bad) }
        // A failure only on the last guard's reconstruction must still precede guard one.
        var laterReads = 0
        assertDeniedBeforeGuard(original, mapper({ if (++laterReads == 3) error("late copy error") else Id("one") }), 2)
        assertEquals(3, laterReads)
    }

    @Test
    fun `fresh same type same scalar concept with different round trip JSON denies before guards`(): Unit = runBlocking {
        val original = Id("one")
        val decoded = mutableListOf<Id>()
        val encoded = mutableListOf<Id>()
        val codec = mapper({ wire ->
            assertEquals("wire:one", wire)
            Id("one").also { copy ->
                assertNotSame(original, copy)
                assertEquals(original.javaClass, copy.javaClass)
                assertEquals(original.value(), copy.value())
                decoded.add(copy)
            }
        }, { value, generator ->
            encoded.add(value)
            generator.writeString(if (value === original) "wire:one" else "different:one")
        })
        assertDeniedBeforeGuard(original, codec, 2)
        assertEquals(1, decoded.size, "The fresh preflight copy must fail JSON consistency, not identity or scalar checks")
        assertEquals(2, encoded.size)
        assertSame(original, encoded[0])
        assertSame(decoded.single(), encoded[1])
        assertEquals("one", decoded.single().value())
        assertEquals("one", original.value())
    }

    @Test
    fun `active cancellation propagates from capture and reconstruction even when codecs throw ordinary errors`(): Unit = runBlocking {
        withTimeout(5_000) {
            for (duringCapture in listOf(true, false)) {
                var job: Job? = null
                var returned = false
                var calls = 0
                val codec = mapper({
                    if (!duringCapture) { job!!.cancel(); error("decode after cancellation") }
                    Id("one")
                }, { value, generator ->
                    if (duringCapture) { job!!.cancel(); error("encode after cancellation") }
                    generator.writeString("wire:${value.value()}")
                })
                val task = async(start = CoroutineStart.LAZY) {
                    job = currentCoroutineContext()[Job]
                    DefaultObservableQueryEmissionGuards(listOf(BlockingObservableQueryEmissionGuard { calls++; ObservableQueryEmissionVerdict.ALLOW }), codec)
                        .guard(context(Id("one")))
                    returned = true
                }
                task.start()
                assertThrows(CancellationException::class.java) { runBlocking { task.await() } }
                assertFalse(returned)
                assertEquals(0, calls)
            }
        }
    }

    @Test
    fun `caller cancellation while awaiting propagates but independently cancelled future denies`(): Unit = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val future = CompletableFuture<ObservableQueryEmissionVerdict>()
            val guards = DefaultObservableQueryEmissionGuards(listOf(GuardObservableQueryEmission { entered.complete(Unit); future }))
            var returned = false
            val task = async { guards.guard(context(1)); returned = true }
            entered.await()
            task.cancel()
            task.join()
            assertTrue(future.isCancelled)
            assertFalse(returned)
            val cancelled = CompletableFuture<ObservableQueryEmissionVerdict>().also { it.cancel(false) }
            assertEquals(ObservableQueryEmissionVerdict.DENY_AND_TERMINATE,
                DefaultObservableQueryEmissionGuards(listOf(GuardObservableQueryEmission { cancelled })).guard(context(1)))
        }
    }

    @Test
    fun `ordering suppression deny and synchronous or stage exceptions stay restrictive`(): Unit = runBlocking {
        val calls = mutableListOf<Int>()
        val guards = DefaultObservableQueryEmissionGuards(listOf(
            BlockingObservableQueryEmissionGuard { calls.add(1); ObservableQueryEmissionVerdict.SUPPRESS },
            BlockingObservableQueryEmissionGuard { calls.add(2); ObservableQueryEmissionVerdict.ALLOW }
        ))
        assertEquals(ObservableQueryEmissionVerdict.SUPPRESS, guards.guard(context(1)))
        assertEquals(listOf(1, 2), calls)
        listOf(
            GuardObservableQueryEmission { throw IllegalStateException("sync") },
            GuardObservableQueryEmission { CompletableFuture.failedFuture(IllegalStateException("async")) },
            BlockingObservableQueryEmissionGuard { ObservableQueryEmissionVerdict.DENY_AND_TERMINATE }
        ).forEach { first ->
            var later = false
            val chain = DefaultObservableQueryEmissionGuards(listOf(first, BlockingObservableQueryEmissionGuard { later = true; ObservableQueryEmissionVerdict.ALLOW }))
            assertEquals(ObservableQueryEmissionVerdict.DENY_AND_TERMINATE, chain.guard(context(1)))
            assertFalse(later)
        }
    }

    @Test
    fun `depth node and aggregate reconstruction limits reject rather than truncate`(): Unit = runBlocking {
        var nested: Any = 1
        repeat(63) { nested = listOf(nested) }
        val allow = BlockingObservableQueryEmissionGuard { ObservableQueryEmissionVerdict.ALLOW }
        assertEquals(ObservableQueryEmissionVerdict.ALLOW, DefaultObservableQueryEmissionGuards(listOf(allow)).guard(context(nested)))
        assertDeniedBeforeGuard(listOf(nested))
        assertEquals(ObservableQueryEmissionVerdict.ALLOW, DefaultObservableQueryEmissionGuards(List(9) { allow }).guard(context(List(9_998) { 1 })))
        assertDeniedBeforeGuard(List(9_999) { 1 })
        assertDeniedBeforeGuard(IntArray(10_000))
        assertDeniedBeforeGuard(List(9_998) { 1 }, count = 10)
        var dag: Any = 1
        repeat(14) { dag = listOf(dag, dag) }
        assertDeniedBeforeGuard(dag)
    }

    @Test
    fun `exact immutable scalar classes and map order survive capture without JSON erasure`(): Unit = runBlocking {
        val values = listOf(
            "one", true, 1.toByte(), 2.toShort(), 3, 4L, 5f, 6.0, 'x', java.math.BigInteger("7"), java.math.BigDecimal("8.00"),
            UUID.randomUUID(), java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.LocalDate.of(2026, 1, 1),
            java.time.LocalTime.of(1, 2, 3), java.time.LocalDateTime.of(2026, 1, 1, 1, 2),
            java.time.OffsetDateTime.parse("2026-01-01T01:02:00+02:00"), java.time.OffsetTime.parse("01:02:00+02:00"),
            java.time.ZonedDateTime.parse("2026-01-01T01:02:00+01:00[Europe/Oslo]"), java.time.Duration.ofSeconds(1),
            java.time.Period.ofDays(1), java.time.Year.of(2026), java.time.YearMonth.of(2026, 1), java.time.MonthDay.of(1, 1)
        )
        val arguments = linkedMapOf("second" to values, "first" to null)
        var observed: Map<*, *>? = null
        val guards = DefaultObservableQueryEmissionGuards(listOf(BlockingObservableQueryEmissionGuard {
            observed = it.arguments["value"] as Map<*, *>
            ObservableQueryEmissionVerdict.ALLOW
        }))
        assertEquals(ObservableQueryEmissionVerdict.ALLOW, guards.guard(context(arguments)))
        val captured = requireNotNull(observed)
        assertEquals(listOf("second", "first"), captured.keys.toList())
        val copied = captured["second"] as List<*>
        assertEquals(values, copied)
        assertEquals(values.map { it.javaClass }, copied.map { it!!.javaClass })
        assertNotSame(values, copied)
    }

    @Test
    fun `arrays retain assignable component classes and unsupported concrete containers fail closed`(): Unit = runBlocking {
        val arrays = listOf(
            booleanArrayOf(true), byteArrayOf(1), shortArrayOf(2), intArrayOf(3), longArrayOf(4),
            floatArrayOf(5f), doubleArrayOf(6.0), charArrayOf('x'), arrayOf<Int?>(null, 1),
            arrayOf<Number>(1, 2L), arrayOf<Any?>(null, listOf(1)), arrayOf(intArrayOf(1)),
            arrayOf<ConceptAs<*>>(Id("one")), arrayOf(arrayListOf(1))
        )
        arrays.forEach { array ->
            var observed: Any? = null
            val guards = DefaultObservableQueryEmissionGuards(listOf(BlockingObservableQueryEmissionGuard {
                observed = it.arguments["value"]; ObservableQueryEmissionVerdict.ALLOW
            }))
            assertEquals(ObservableQueryEmissionVerdict.ALLOW, guards.guard(context(array)))
            assertEquals(array.javaClass, observed!!.javaClass)
            assertNotSame(array, observed)
            assertEquals(java.lang.reflect.Array.getLength(array), java.lang.reflect.Array.getLength(observed))
        }
        assertDeniedBeforeGuard(arrayOf(java.util.LinkedList(listOf(1))))
        assertDeniedBeforeGuard(java.util.concurrent.atomic.AtomicInteger(1))
        assertDeniedBeforeGuard(java.util.Date())
        assertDeniedBeforeGuard(setOf(1))
    }

    @Test
    fun `plain JDK enums now retain checked singleton identity rather than blanket rejection`(): Unit = runBlocking {
        var calls = 0
        val guards = DefaultObservableQueryEmissionGuards(listOf(BlockingObservableQueryEmissionGuard {
            assertSame(Thread.State.NEW, it.arguments["value"])
            calls++
            ObservableQueryEmissionVerdict.ALLOW
        }))
        assertEquals(ObservableQueryEmissionVerdict.ALLOW, guards.guard(context(Thread.State.NEW)))
        assertEquals(1, calls)
    }

    private suspend fun assertDeniedBeforeGuard(value: Any, mapper: ObjectMapper = ArcObjectMapper.create(), count: Int = 1) {
        var calls = 0
        val guards = DefaultObservableQueryEmissionGuards(List(count) { BlockingObservableQueryEmissionGuard { calls++; ObservableQueryEmissionVerdict.ALLOW } }, mapper)
        assertEquals(ObservableQueryEmissionVerdict.DENY_AND_TERMINATE, guards.guard(context(value)))
        assertEquals(0, calls)
    }

    private fun mapper(
        decode: (String) -> Id,
        encode: (Id, JsonGenerator) -> Unit = { value, generator -> generator.writeString("wire:${value.value()}") }
    ): ObjectMapper = JsonMapper.builder().addModule(SimpleModule()
        .addSerializer(Id::class.java, object : ValueSerializer<Id>() {
            override fun serialize(value: Id, generator: JsonGenerator, context: SerializationContext) = encode(value, generator)
        })
        .addDeserializer(Id::class.java, object : ValueDeserializer<Id>() {
            override fun deserialize(parser: JsonParser, context: DeserializationContext): Id = decode(parser.string)
        })).build()

    private fun context(value: Any) = ObservableQueryEmissionContext(
        FullyQualifiedQueryName("Tests.observe"), mapOf("value" to value), ArcPrincipal.anonymous(), null, null, UUID.randomUUID(),
        object : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null }, true, null
    )

    internal class Id(private val value: String) : ConceptAs<String> { override fun value(): String = value }
}
