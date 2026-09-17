// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.crosscuttingauthorization;

import io.cratis.arc.artifacts.ReadModel;
import java.time.OffsetDateTime;

/**
 * The read model a query filter guards without any annotation on this file.
 *
 * Nothing here says "authorized". The rule lives in {@code CrossCuttingAuthorizationQueryFilter},
 * which matches on the package name — which is the whole idea of a cross-cutting rule: one place
 * decides for an entire feature, and adding a new query to the package inherits the rule for free.
 *
 * @param message A human-readable status message.
 * @param checkedAt The timestamp for when the status was generated.
 */
@ReadModel
public record CrossCuttingAuthorizationStatus(String message, OffsetDateTime checkedAt) {
    /**
     * Gets the current secured status.
     *
     * @return A status describing the secured query execution.
     */
    public static CrossCuttingAuthorizationStatus secured() {
        return new CrossCuttingAuthorizationStatus(
            "Query authorized and executed through a custom query filter.",
            OffsetDateTime.now());
    }
}
