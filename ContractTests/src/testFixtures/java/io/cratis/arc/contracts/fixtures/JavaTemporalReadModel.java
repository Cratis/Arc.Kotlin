// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.ReadModel;

/**
 * Java read model fixture covering direct JVM temporal and identifier values.
 *
 * @param identifier Java model identifier documented by a param tag.
 * @param date Java model date documented by a param tag.
 * @param time Java model time documented by a param tag.
 */
@ReadModel
public record JavaTemporalReadModel(
    java.util.UUID identifier,
    java.time.LocalDate date,
    java.time.LocalTime time
) {
    /**
     * Returns a typed model from direct JVM temporal and identifier query parameters.
     *
     * @param date Java query date argument.
     * @param identifier Java query identifier argument.
     * @param time Java query time argument.
     * @return the model
     */
    public static JavaTemporalReadModel findJavaTemporal(
        java.util.UUID identifier,
        java.time.LocalDate date,
        java.time.LocalTime time
    ) {
        return new JavaTemporalReadModel(identifier, date, time);
    }
}
