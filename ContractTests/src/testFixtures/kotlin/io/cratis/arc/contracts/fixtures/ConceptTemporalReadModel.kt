// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.ReadModel

/** Read model keeping concept-backed temporal and identifier mappings separate from direct JVM mappings. */
@ReadModel
public data class ConceptTemporalReadModel(
    public val identifier: OrderId,
    public val date: DeliveryDate,
    public val time: DeliveryTime,
    public val javaIdentifier: JavaOrderId,
    public val javaDate: JavaDeliveryDate,
    public val javaTime: JavaDeliveryTime
) {
    public companion object {
        /** Returns a concept-backed model from concept-backed query parameters. */
        public fun findConceptTemporal(
            identifier: OrderId,
            date: DeliveryDate,
            time: DeliveryTime,
            javaIdentifier: JavaOrderId,
            javaDate: JavaDeliveryDate,
            javaTime: JavaDeliveryTime
        ): ConceptTemporalReadModel = ConceptTemporalReadModel(
            identifier,
            date,
            time,
            javaIdentifier,
            javaDate,
            javaTime
        )
    }
}
