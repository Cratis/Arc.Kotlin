// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@ReadModel
@AllowAnonymous
public data class KotlinFundamentalsExclusionView(public val value: String) {
    public companion object {
        public fun findKotlinFundamentalsExclusions(input: KotlinFundamentalsExclusionInput? = null): KotlinFundamentalsExclusionView =
            KotlinFundamentalsExclusionView(input?.ignored?.value() ?: "absent")
        public fun observeKotlinFundamentalsExclusions(input: KotlinFundamentalsExclusionInput? = null): Flow<KotlinFundamentalsExclusionView> =
            flowOf(findKotlinFundamentalsExclusions(input))
    }
}
