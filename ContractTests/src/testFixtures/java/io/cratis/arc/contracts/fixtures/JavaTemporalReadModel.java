// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.ReadModel;

/** Java read model fixture covering direct JVM temporal and identifier values. */
@ReadModel
public record JavaTemporalReadModel(
    java.util.UUID identifier,
    java.time.LocalDate date,
    java.time.LocalTime time
) {
    /** Returns a typed model from direct JVM temporal and identifier query parameters. */
    public static JavaTemporalReadModel findJavaTemporal(
        java.util.UUID identifier,
        java.time.LocalDate date,
        java.time.LocalTime time
    ) {
        return new JavaTemporalReadModel(identifier, date, time);
    }
}
