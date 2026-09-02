// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

/** Typed Java response preserving direct JVM temporal and identifier values. */
public record JavaTemporalResult(
    java.util.UUID identifier,
    java.time.LocalDate date,
    java.time.LocalTime time
) {
}
