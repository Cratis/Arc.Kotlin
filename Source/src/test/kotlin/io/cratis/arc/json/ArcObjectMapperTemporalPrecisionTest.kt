// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

import com.fasterxml.jackson.databind.JsonMappingException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Establishes where Arc's wire format stops preserving temporal precision, per type.
 *
 * .NET counts time in 100 ns ticks, and Arc's `LocalTime` contract is written to that boundary: seven fractional
 * digits are accepted, eight or nine are refused on read, and a value finer than 100 ns is refused on write rather
 * than quietly rounded. No other temporal type carries that guard. `Instant`, `OffsetDateTime`, `ZonedDateTime`,
 * `LocalDateTime`, and `Duration` go through `JavaTimeModule` as ISO-8601 text and keep full JVM nanosecond
 * precision, which is finer than a .NET tick can hold. `LocalDate` and `UUID` have no sub-second component at all.
 */
class ArcObjectMapperTemporalPrecisionTest {
    private val mapper = ArcObjectMapper.create()

    @Test
    fun `local time keeps one hundred nanosecond precision as seven fractional digits`() {
        val value = LocalTime.of(8, 9, 10, 123_456_700)

        assertEquals("\"08:09:10.1234567\"", mapper.writeValueAsString(value))
        assertEquals(value, mapper.readValue("\"08:09:10.1234567\"", LocalTime::class.java))
    }

    @Test
    fun `local time refuses to write a value finer than one hundred nanoseconds`() {
        assertThrows(JsonMappingException::class.java) {
            mapper.writeValueAsString(LocalTime.of(8, 9, 10, 123_456_780))
        }
        assertThrows(JsonMappingException::class.java) {
            mapper.writeValueAsString(LocalTime.of(8, 9, 10, 123_456_789))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["08:09:10.12345678", "08:09:10.123456789"])
    fun `local time refuses to read eight or nine fractional digits`(text: String) {
        assertThrows(JsonMappingException::class.java) {
            mapper.readValue("\"$text\"", LocalTime::class.java)
        }
    }

    @Test
    fun `instant keeps full nanosecond precision, which is finer than a dotnet tick`() {
        val value = Instant.parse("2025-02-03T08:09:10.123456789Z")

        assertEquals("\"2025-02-03T08:09:10.123456789Z\"", mapper.writeValueAsString(value))
        assertEquals(value, mapper.readValue("\"2025-02-03T08:09:10.123456789Z\"", Instant::class.java))
    }

    @Test
    fun `offset date time keeps every nanosecond but reads back normalized to UTC`() {
        val value = OffsetDateTime.of(2025, 2, 3, 8, 9, 10, 123_456_789, ZoneOffset.ofHours(2))

        assertEquals("\"2025-02-03T08:09:10.123456789+02:00\"", mapper.writeValueAsString(value))

        val roundTripped = mapper.readValue("\"2025-02-03T08:09:10.123456789+02:00\"", OffsetDateTime::class.java)

        assertEquals(value.toInstant(), roundTripped.toInstant())
        assertEquals(123_456_789, roundTripped.nano)
        assertEquals(ZoneOffset.UTC, roundTripped.offset)
        assertNotEquals(value, roundTripped)
    }

    @Test
    fun `zoned date time keeps full nanosecond precision`() {
        val value = ZonedDateTime.of(2025, 2, 3, 8, 9, 10, 123_456_789, ZoneOffset.UTC)

        assertEquals("\"2025-02-03T08:09:10.123456789Z\"", mapper.writeValueAsString(value))
        assertEquals(
            value.toInstant(),
            mapper.readValue("\"2025-02-03T08:09:10.123456789Z\"", ZonedDateTime::class.java).toInstant()
        )
    }

    @Test
    fun `local date time keeps full nanosecond precision`() {
        val value = LocalDateTime.of(2025, 2, 3, 8, 9, 10, 123_456_789)

        assertEquals("\"2025-02-03T08:09:10.123456789\"", mapper.writeValueAsString(value))
        assertEquals(value, mapper.readValue("\"2025-02-03T08:09:10.123456789\"", LocalDateTime::class.java))
    }

    @Test
    fun `duration keeps full nanosecond precision`() {
        val value = Duration.ofSeconds(1, 123_456_789)

        assertEquals("\"PT1.123456789S\"", mapper.writeValueAsString(value))
        assertEquals(value, mapper.readValue("\"PT1.123456789S\"", Duration::class.java))
    }

    @Test
    fun `local date has no sub-day component to lose`() {
        val value = LocalDate.of(2025, 2, 3)

        assertEquals("\"2025-02-03\"", mapper.writeValueAsString(value))
        assertEquals(value, mapper.readValue("\"2025-02-03\"", LocalDate::class.java))
    }

    @Test
    fun `uuid round trips as lowercase dashed text without loss`() {
        val value = UUID.fromString("3c6b4a11-4512-4cb6-b67d-2edbc7543d5b")

        assertEquals("\"3c6b4a11-4512-4cb6-b67d-2edbc7543d5b\"", mapper.writeValueAsString(value))
        assertEquals(value, mapper.readValue("\"3C6B4A11-4512-4CB6-B67D-2EDBC7543D5B\"", UUID::class.java))
    }
}
