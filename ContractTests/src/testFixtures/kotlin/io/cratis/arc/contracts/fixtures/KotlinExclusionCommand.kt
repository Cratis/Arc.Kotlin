// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.Command
import io.cratis.arc.authorization.AllowAnonymous
import jakarta.validation.Valid

/** Exercises server-side validation without changing generated exclusion metadata. */
@Command
@AllowAnonymous
public data class KotlinExclusionCommand(@field:Valid public val input: KotlinExclusionInput) {
    public fun handle(): String = "handled"
}
