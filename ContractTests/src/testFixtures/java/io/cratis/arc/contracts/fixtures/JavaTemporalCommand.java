// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.artifacts.Command;

/** Java command fixture covering direct JVM temporal and identifier values. */
@Command
public record JavaTemporalCommand(
    java.util.UUID identifier,
    java.time.LocalDate date,
    java.time.LocalTime time
) {
    /** Returns the direct values through the generated command handler. */
    public JavaTemporalResult handle() {
        return new JavaTemporalResult(identifier, date, time);
    }
}
