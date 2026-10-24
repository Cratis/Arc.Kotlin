// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.concepts

import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.queries.ObservableQueryArgumentSnapshot
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ConceptValidation
import io.cratis.arc.validation.ConceptValidator
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FundamentalsConceptAdoptionTest {
    private val mapper = ArcObjectMapper.create()

    @Test
    fun `fundamentals concepts round trip as bare scalar values`() {
        val id = UUID.fromString("3c6b4a11-4512-4cb6-b67d-2edbc7543d5b")
        assertRoundTrip("\"$id\"", OrderId(id), OrderId::class.java)
        assertRoundTrip("42", Count(42), Count::class.java)
        assertRoundTrip("1", StatusCode(Status.Second), StatusCode::class.java)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `legacy implementers remain fundamentals concepts and round trip`() {
        val legacy: Any = LegacyCode("hello")
        assertTrue(legacy is io.cratis.fundamentals.concepts.ConceptAs<*>)
        assertRoundTrip("\"hello\"", legacy, LegacyCode::class.java)
    }

    @Test
    fun `fundamentals concept validators run through concept validation`() {
        val validator = object : ConceptValidator<OrderId> {
            override val conceptType = OrderId::class.java
            override fun validate(concept: OrderId) = listOf(ValidationResult.error("order"))
        }
        val results = ConceptValidation(listOf(validator)).validate(OrderId(UUID.randomUUID()), "id")
        assertEquals(listOf("order"), results.map { it.message })
        assertEquals(listOf("id"), results.flatMap { it.members })
    }

    @Suppress("DEPRECATION")
    @Test
    fun `argument snapshots copy fundamentals and legacy concepts alike`() {
        val id = OrderId(UUID.randomUUID())
        val legacy = LegacyCode("hello")
        val copies = ObservableQueryArgumentSnapshot(mapper, EmptyCoroutineContext)
            .copies(mapOf("id" to id, "legacy" to legacy), 2)
        copies.forEach {
            assertEquals(id, it["id"])
            assertEquals(legacy, it["legacy"])
            assertNotSame(id, it["id"])
            assertNotSame(legacy, it["legacy"])
        }
        assertNotSame(copies[0]["id"], copies[1]["id"])
    }

    private fun <T> assertRoundTrip(json: String, value: T, type: Class<out T>) {
        assertEquals(json, mapper.writeValueAsString(value))
        assertEquals(value, mapper.readValue(json, type))
    }

    data class OrderId(private val id: UUID) : io.cratis.fundamentals.concepts.ConceptAs<UUID> {
        override fun value(): UUID = id
    }

    data class Count(private val count: Int) : io.cratis.fundamentals.concepts.ConceptAs<Int> {
        override fun value(): Int = count
    }

    enum class Status { First, Second }

    data class StatusCode(private val status: Status) : io.cratis.fundamentals.concepts.ConceptAs<Status> {
        override fun value(): Status = status
    }

    @Suppress("DEPRECATION")
    data class LegacyCode(private val code: String) : io.cratis.arc.concepts.ConceptAs<String> {
        override fun value(): String = code
    }
}
