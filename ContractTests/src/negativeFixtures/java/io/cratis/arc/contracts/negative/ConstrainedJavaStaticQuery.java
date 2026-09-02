// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.artifacts.ReadModel;
import jakarta.validation.constraints.NotBlank;

/** Static Java query constraints are rejected until executable validation has a receiver-safe contract. */
@ReadModel
public record ConstrainedJavaStaticQuery(String value) {
    public static ConstrainedJavaStaticQuery find(@NotBlank String value) {
        return new ConstrainedJavaStaticQuery(value);
    }
}
