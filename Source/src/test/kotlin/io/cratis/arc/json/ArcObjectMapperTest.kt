// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import io.cratis.arc.concepts.ArcEnum
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.polymorphism.ConcurrentDerivedTypeRegistry
import io.cratis.arc.polymorphism.DerivedType
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ArcObjectMapperTest {
    private val mapper = ArcObjectMapper.create()

    @Test
    fun `ordinary enums write ordinal and read case-insensitive names`() {
        assertEquals("1", mapper.writeValueAsString(OrdinaryEnum.Second))
        assertEquals(OrdinaryEnum.Second, mapper.readValue("\"sEcOnD\"", OrdinaryEnum::class.java))
    }

    @Test
    fun `ordinary enums reject undefined integers`() {
        assertThrows(JsonMappingException::class.java) {
            mapper.readValue("2", OrdinaryEnum::class.java)
        }
    }

    @Test
    fun `ArcEnum writes and reads explicit values`() {
        assertEquals("42", mapper.writeValueAsString(ExplicitEnum.Answer))
        assertEquals(ExplicitEnum.Answer, mapper.readValue("42", ExplicitEnum::class.java))
        assertEquals(ExplicitEnum.Answer, mapper.readValue("\"aNsWeR\"", ExplicitEnum::class.java))
    }

    @Test
    fun `string integer UUID and list concepts round trip as their values`() {
        val orderId = UUID.fromString("3c6b4a11-4512-4cb6-b67d-2edbc7543d5b")

        assertRoundTrip("\"hello\"", StringConcept("hello"), StringConcept::class.java)
        assertRoundTrip("42", IntConcept(42), IntConcept::class.java)
        assertRoundTrip("\"$orderId\"", UuidConcept(orderId), UuidConcept::class.java)
        assertRoundTrip("[\"one\",\"two\"]", StringListConcept(listOf("one", "two")), StringListConcept::class.java)
    }

    @Test
    fun `derived types use discriminator and honor Jackson annotations`() {
        val registry = ConcurrentDerivedTypeRegistry()
        registry.register(Animal::class.java, Cat::class.java)
        val derivedMapper = ArcObjectMapper.create(registry)

        val json = derivedMapper.writeValueAsString(Cat("Milo", "server-only"))
        val tree = derivedMapper.readTree(json)

        assertEquals("cat", tree["_derivedTypeId"].textValue())
        assertEquals("Milo", tree["display_name"].textValue())
        assertFalse(tree.has("name"))
        assertFalse(tree.has("internalNote"))
        assertEquals(Cat("Milo"), derivedMapper.readValue(json, Animal::class.java))
    }

    @Test
    fun `local date and local time use dotnet-compatible text`() {
        val value = DateAndTime(
            LocalDate.of(2025, 2, 3),
            LocalTime.of(8, 9, 10, 123_456_700)
        )

        val json = mapper.writeValueAsString(value)
        val tree = mapper.readTree(json)

        assertEquals("2025-02-03", tree["date"].textValue())
        assertEquals("08:09:10.1234567", tree["time"].textValue())
        assertEquals(value, mapper.readValue(json, DateAndTime::class.java))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "03:04:05",
            "03:04:05.1",
            "03:04:05.12",
            "03:04:05.123",
            "03:04:05.1234",
            "03:04:05.12345",
            "03:04:05.123456",
            "03:04:05.1234567"
        ]
    )
    fun `local time accepts no fraction through seven fractional digits`(time: String) {
        assertRoundTrip("\"$time\"", LocalTime.parse(time), LocalTime::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["24:00:00", "03:60:05", "03:04:60"])
    fun `local time rejects values outside valid ranges`(time: String) {
        assertThrows(JsonMappingException::class.java) {
            mapper.readValue("\"$time\"", LocalTime::class.java)
        }
    }

    @Test
    fun `local time valid upper boundary round trips`() {
        val time = LocalTime.of(23, 59, 59, 999_999_900)

        assertRoundTrip("\"23:59:59.9999999\"", time, LocalTime::class.java)
    }

    @Test
    fun `local time serialization rejects precision finer than one hundred nanoseconds`() {
        val time = LocalTime.of(3, 4, 5, 123_456_789)

        assertThrows(JsonMappingException::class.java) {
            mapper.writeValueAsString(time)
        }
    }

    @Test
    fun `local time concepts inherit strict range validation`() {
        assertThrows(JsonMappingException::class.java) {
            mapper.readValue("\"24:00:00\"", LocalTimeConcept::class.java)
        }
    }

    @Test
    fun `duration uses ISO-8601 text including fractional and negative values`() {
        assertRoundTrip("\"PT1.5S\"", Duration.ofMillis(1_500), Duration::class.java)
        assertRoundTrip("\"PT-1.5S\"", Duration.ofMillis(-1_500), Duration::class.java)
    }

    @Test
    fun `period already uses ISO-8601 text`() {
        assertRoundTrip("\"P1Y2M3D\"", Period.of(1, 2, 3), Period::class.java)
    }

    @Test
    fun `empty arrays are retained and null values are omitted`() {
        val tree = mapper.valueToTree<JsonNode>(OptionalPayload())

        assertEquals(setOf("values"), tree.fieldNames().asSequence().toSet())
        assertTrue(tree["values"].isArray)
        assertTrue(tree["values"].isEmpty)
        assertFalse(tree.has("optional"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["__proto__", "prototype", "constructor"])
    fun `reserved string map keys are rejected on write and read`(key: String) {
        val value = StringMapPayload(mapOf(key to "unsafe"))

        assertThrows(JsonMappingException::class.java) {
            mapper.writeValueAsString(value)
        }
        assertThrows(JsonMappingException::class.java) {
            mapper.readValue("""{"values":{"$key":"unsafe"}}""", StringMapPayload::class.java)
        }
    }

    @Test
    fun `near miss string map keys round trip`() {
        val value = StringMapPayload(
            linkedMapOf("__proto" to "one", "Prototype" to "two", "constructor_" to "three")
        )

        assertRoundTrip(mapper.writeValueAsString(value), value, StringMapPayload::class.java)
    }

    @Test
    fun `named floating point values write as strings and round trip`() {
        val value = NamedFloats(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        val json = mapper.writeValueAsString(value)
        val tree = mapper.readTree(json)
        val roundTripped = mapper.readValue(json, NamedFloats::class.java)

        assertEquals("NaN", tree["notANumber"].textValue())
        assertEquals("Infinity", tree["positiveInfinity"].textValue())
        assertEquals("-Infinity", tree["negativeInfinity"].textValue())
        assertTrue(roundTripped.notANumber.isNaN())
        assertEquals(Double.POSITIVE_INFINITY, roundTripped.positiveInfinity)
        assertEquals(Double.NEGATIVE_INFINITY, roundTripped.negativeInfinity)
    }

    private fun <T> assertRoundTrip(expectedJson: String, value: T, type: Class<T>) {
        val json = mapper.writeValueAsString(value)
        assertEquals(expectedJson, json)
        assertEquals(value, mapper.readValue(json, type))
    }
}

enum class OrdinaryEnum {
    First,
    Second
}

enum class ExplicitEnum(private val wireValue: Int) : ArcEnum {
    First(7),
    Answer(42);

    override fun value(): Int = wireValue
}

data class StringConcept(private val rawValue: String) : ConceptAs<String> {
    override fun value(): String = rawValue
}

data class IntConcept(private val rawValue: Int) : ConceptAs<Int> {
    override fun value(): Int = rawValue
}

data class UuidConcept(private val rawValue: UUID) : ConceptAs<UUID> {
    override fun value(): UUID = rawValue
}

data class StringListConcept(private val rawValue: List<String>) : ConceptAs<List<String>> {
    override fun value(): List<String> = rawValue
}

data class LocalTimeConcept(private val rawValue: LocalTime) : ConceptAs<LocalTime> {
    override fun value(): LocalTime = rawValue
}

interface Animal

@DerivedType("cat")
data class Cat(
    @param:JsonProperty("display_name")
    @get:JsonProperty("display_name")
    val name: String,
    @get:JsonIgnore
    val internalNote: String = "default"
) : Animal

data class DateAndTime(val date: LocalDate, val time: LocalTime)

data class OptionalPayload(val values: List<String> = emptyList(), val optional: String? = null)

data class StringMapPayload(val values: Map<String, String>)

data class NamedFloats(
    val notANumber: Double,
    val positiveInfinity: Double,
    val negativeInfinity: Double
)
