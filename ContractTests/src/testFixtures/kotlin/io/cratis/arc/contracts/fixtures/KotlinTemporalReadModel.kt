// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.ReadModel
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Kotlin read model fixture covering direct JVM temporal and identifier values. */
@ReadModel
public data class KotlinTemporalReadModel(
    public val identifier: UUID,
    public val date: LocalDate,
    public val time: LocalTime
) {
    public companion object {
        /** Returns a typed model from direct JVM temporal and identifier query parameters. */
        public fun findKotlinTemporal(
            identifier: UUID,
            date: LocalDate,
            time: LocalTime
        ): KotlinTemporalReadModel = KotlinTemporalReadModel(identifier, date, time)
    }
}
