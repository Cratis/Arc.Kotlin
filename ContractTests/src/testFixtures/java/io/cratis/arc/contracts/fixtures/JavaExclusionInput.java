// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Record declaration order intentionally visits the excluded edge before the required edge. */
public record JavaExclusionInput(@Valid @NotNull @Nullable JavaCustomerCode ignored, @Valid @Nullable JavaCustomerCode required) { }
