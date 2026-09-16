// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import jakarta.validation.Valid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Generated snapshot and one-shot invocations with a nullable, defaulted nested input. */
@ReadModel
@AllowAnonymous
public data class KotlinExclusionView(public val value: String) {
    public companion object {
        public fun findKotlinExclusions(@Valid input: KotlinExclusionInput? = null): KotlinExclusionView =
            KotlinExclusionView(input?.ignored?.value() ?: "absent")
        public fun observeKotlinExclusions(@Valid input: KotlinExclusionInput? = null): Flow<KotlinExclusionView> =
            flowOf(findKotlinExclusions(input))
    }
}
