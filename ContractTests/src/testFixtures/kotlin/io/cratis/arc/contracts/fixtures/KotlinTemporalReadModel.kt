// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.ReadModel
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Kotlin read model fixture covering direct JVM temporal and identifier values.
 *
 * The second paragraph must never reach generated documentation.
 *
 * @property date Kotlin model delivery date documented by a property tag.
 */
@ReadModel
public data class KotlinTemporalReadModel(
    /** Stable Kotlin model identifier. */
    public val identifier: UUID,
    public val date: LocalDate,
    /** Kotlin model time /* nested */ terminator and @tag safety. */
    public val time: LocalTime
) {
    public companion object {
        /**
         * Returns a typed model from direct JVM temporal and identifier query parameters.
         *
         * @param time Kotlin query time argument.
         */
        public fun findKotlinTemporal(
            identifier: UUID,
            date: LocalDate,
            time: LocalTime
        ): KotlinTemporalReadModel = KotlinTemporalReadModel(identifier, date, time)
    }
}
