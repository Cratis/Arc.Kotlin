// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.queries.Path
import io.cratis.arc.queries.QueryHttpMethod
import io.cratis.arc.queries.QueryHttpMethodType
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Typed calendar and identifier values returned by both the command and query contracts. */
@ReadModel
@AllowAnonymous
public data class CalendarEcho(
    public val identifier: UUID,
    public val date: LocalDate,
    public val time: LocalTime
) {
    public companion object {
        /** Echoes direct calendar and identifier query parameters through generated query hosting. */
        @JvmStatic
        @Path("/api/calendar-echo")
        public fun findCalendarEcho(identifier: UUID, date: LocalDate, time: LocalTime): CalendarEcho =
            CalendarEcho(identifier, date, time)

        /** Uses a Kotlin default when the GET year argument is absent. */
        @JvmStatic
        @Path("/api/calendar-default-get")
        @QueryHttpMethod(QueryHttpMethodType.GET)
        public fun findCalendarDefaultGet(year: Int = 2026): CalendarEcho = defaultCalendar(year)

        /** Uses a nullable Kotlin default when the RFC QUERY year argument is absent. */
        @JvmStatic
        @Path("/api/calendar-default-query")
        @QueryHttpMethod(QueryHttpMethodType.QUERY)
        public fun findCalendarDefaultQuery(year: Int? = 2027): CalendarEcho = defaultCalendar(year ?: 2000)

        /** Returns a fixed seven-fractional-digit time to verify full JVM wire precision. */
        @JvmStatic
        @Path("/api/calendar-precision")
        public fun findCalendarPrecision(): CalendarEcho =
            CalendarEcho(
                DEFAULT_IDENTIFIER,
                LocalDate.of(2026, 1, 1),
                LocalTime.of(8, 9, 10, 123_556_700)
            )

        private val DEFAULT_IDENTIFIER: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    }
}

private fun defaultCalendar(year: Int): CalendarEcho = CalendarEcho(
    UUID.fromString("11111111-1111-1111-1111-111111111111"),
    LocalDate.of(year, 1, 1),
    LocalTime.of(8, 9, 10)
)

/** Echoes direct calendar and identifier command values through generated command hosting. */
@Command
@AllowAnonymous
public data class EchoCalendar(
    public val identifier: UUID,
    public val date: LocalDate,
    public val time: LocalTime
) {
    /** Returns the direct values as the shared typed response and read model. */
    public fun handle(): CalendarEcho = CalendarEcho(identifier, date, time)
}
