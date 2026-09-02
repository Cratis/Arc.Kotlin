// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.Command
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Typed response preserving direct JVM temporal and identifier values. */
public data class KotlinTemporalResult(
    public val identifier: UUID,
    public val date: LocalDate,
    public val time: LocalTime
)

/** Kotlin command fixture covering direct JVM temporal and identifier values. */
@Command
public data class KotlinTemporalCommand(
    public val identifier: UUID,
    public val date: LocalDate,
    public val time: LocalTime
) {
    /** Returns the direct values through the generated command handler. */
    public fun handle(): KotlinTemporalResult = KotlinTemporalResult(identifier, date, time)
}
