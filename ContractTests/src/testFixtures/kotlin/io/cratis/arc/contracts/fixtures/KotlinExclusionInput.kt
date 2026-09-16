// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull

/** Direct concept edges whose server exclusions must not suppress Jakarta cascading. */
public data class KotlinExclusionInput(
    @field:Valid @field:NotNull public val ignored: JavaCustomerCode?,
    @field:Valid public val required: JavaCustomerCode?
)
