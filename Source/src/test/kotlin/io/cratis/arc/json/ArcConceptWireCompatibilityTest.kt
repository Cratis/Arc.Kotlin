// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import io.cratis.fundamentals.concepts.ConceptAs
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.DatabindException

class ArcConceptWireCompatibilityTest {
    private val mapper = ArcObjectMapper.create()

    private fun <T : Any> roundTrip(json: String, concept: T) {
        assertEquals(json, mapper.writeValueAsString(concept))
        assertEquals(concept, mapper.readValue(json, concept.javaClass))
    }

    @Test
    fun `scalar concepts follow the Arc scalar wire`() {
        roundTrip("\"a\"", WireText("a"))
        roundTrip("42", WireInt(42))
        roundTrip("9007199254740993", WireLong(9007199254740993))
        roundTrip("12", WireShort(12))
        roundTrip("12", WireByte(12))
        roundTrip("1.5", WireDouble(1.5))
        roundTrip("\"NaN\"", WireDouble(Double.NaN))
        roundTrip("1.5", WireFloat(1.5f))
        roundTrip("true", WireBoolean(true))
    }

    @Test
    fun `decimal concepts preserve exponent and scale unlike Fundamentals plain decimals`() {
        for (text in listOf("1E+3", "12.50", "1E-7")) roundTrip(text, WireDecimal(BigDecimal(text)))
    }

    @Test
    fun `UUID reads accept uppercase and writes are lowercase`() {
        val text = "3c6b4a11-4512-4cb6-b67d-2edbc7543d5b"
        roundTrip("\"$text\"", WireUuid(UUID.fromString(text)))
        assertEquals(WireUuid(UUID.fromString(text)), mapper.readValue("\"${text.uppercase()}\"", WireUuid::class.java))
    }

    @Test
    fun `date and time concepts follow Arc precision rules`() {
        roundTrip("\"2026-01-02\"", WireDate(LocalDate.parse("2026-01-02")))
        roundTrip("\"08:09:10\"", WireTime(LocalTime.parse("08:09:10")))
        roundTrip("\"08:09:10.1234567\"", WireTime(LocalTime.parse("08:09:10.1234567")))
        assertThrows(DatabindException::class.java) {
            mapper.writeValueAsString(WireTime(LocalTime.parse("08:09:10.000000001")))
        }
        assertThrows(DatabindException::class.java) { mapper.readValue("\"08:09:10.12345678\"", WireTime::class.java) }
    }

    @Test
    fun `offset date time uses Z and reads normalize to UTC`() {
        roundTrip("\"2026-01-02T03:04:05Z\"", WireOffset(OffsetDateTime.parse("2026-01-02T03:04:05Z")))
        val input = OffsetDateTime.parse("2026-01-02T03:04:05+02:00")
        assertEquals("\"2026-01-02T03:04:05+02:00\"", mapper.writeValueAsString(WireOffset(input)))
        val read = mapper.readValue("\"$input\"", WireOffset::class.java).value()
        assertTrue(read.isEqual(input))
        assertEquals(ZoneOffset.UTC, read.offset)
    }

    @Test
    fun `enum list and nested concepts round trip`() {
        roundTrip("1", WireEnum(WireChoice.Second))
        roundTrip("[\"a\",\"b\"]", WireList(listOf("a", "b")))
        roundTrip("\"a\"", WireOuter(WireText("a")))
    }

    @Test
    fun `null concept properties remain null and absent properties are omitted`() {
        assertNull(mapper.readValue("{\"value\":null}", WireBean::class.java).value)
        assertEquals("{}", mapper.writeValueAsString(mapper.readValue("{}", WireBean::class.java)))
    }

    @Test
    fun `empty string and numeric UUID inputs characterize current coercion`() {
        assertThrows(DatabindException::class.java) { mapper.readValue("\"\"", WireInt::class.java) }
        assertThrows(DatabindException::class.java) { mapper.readValue("\"\"", WireUuid::class.java) }
        assertThrows(DatabindException::class.java) { mapper.readValue("42", WireUuid::class.java) }
    }

    @Test
    fun `constructor errors retain the cause and concept name`() {
        val error = assertThrows(DatabindException::class.java) { mapper.readValue("\"a\"", WireBad::class.java) }
        assertTrue(checkNotNull(error.message).contains(WireBad::class.java.name))
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is IllegalArgumentException && it.message == "bad" })
    }

    @Test
    fun `unconstructible concepts fail only when a value is read`() {
        assertNull(mapper.readValue("{}", WireMissingBean::class.java).value)
        assertThrows(DatabindException::class.java) { mapper.readValue("\"a\"", WireMissing::class.java) }
    }
}

data class WireText(private val raw: String) : ConceptAs<String> { override fun value(): String = raw }
data class WireInt(private val raw: Int) : ConceptAs<Int> { override fun value(): Int = raw }
data class WireLong(private val raw: Long) : ConceptAs<Long> { override fun value(): Long = raw }
data class WireShort(private val raw: Short) : ConceptAs<Short> { override fun value(): Short = raw }
data class WireByte(private val raw: Byte) : ConceptAs<Byte> { override fun value(): Byte = raw }
data class WireDouble(private val raw: Double) : ConceptAs<Double> { override fun value(): Double = raw }
data class WireFloat(private val raw: Float) : ConceptAs<Float> { override fun value(): Float = raw }
data class WireBoolean(private val raw: Boolean) : ConceptAs<Boolean> { override fun value(): Boolean = raw }
data class WireDecimal(private val raw: BigDecimal) : ConceptAs<BigDecimal> { override fun value(): BigDecimal = raw }
data class WireUuid(private val raw: UUID) : ConceptAs<UUID> { override fun value(): UUID = raw }
data class WireDate(private val raw: LocalDate) : ConceptAs<LocalDate> { override fun value(): LocalDate = raw }
data class WireTime(private val raw: LocalTime) : ConceptAs<LocalTime> { override fun value(): LocalTime = raw }
data class WireOffset(private val raw: OffsetDateTime) : ConceptAs<OffsetDateTime> { override fun value(): OffsetDateTime = raw }
enum class WireChoice { First, Second }
data class WireEnum(private val raw: WireChoice) : ConceptAs<WireChoice> { override fun value(): WireChoice = raw }
data class WireList(private val raw: List<String>) : ConceptAs<List<String>> { override fun value(): List<String> = raw }
data class WireOuter(private val raw: WireText) : ConceptAs<WireText> { override fun value(): WireText = raw }
data class WireBean(val value: WireText? = null)
class WireBad(private val raw: String) : ConceptAs<String> {
    init { throw IllegalArgumentException("bad") }
    override fun value(): String = raw
}
class WireMissing private constructor() : ConceptAs<String> { override fun value(): String = "missing" }
data class WireMissingBean(val value: WireMissing? = null)
