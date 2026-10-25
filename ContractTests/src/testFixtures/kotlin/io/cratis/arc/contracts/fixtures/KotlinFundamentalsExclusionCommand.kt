// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.Command
import io.cratis.arc.authorization.AllowAnonymous

@Command
@AllowAnonymous
public data class KotlinFundamentalsExclusionCommand(public val input: KotlinFundamentalsExclusionInput) {
    public fun handle(): String = "handled"
}
