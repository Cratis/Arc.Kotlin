// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.fundamentals.concepts.ConceptAs

public data class KotlinFundamentalsExclusionCode(private val rawValue: String) : ConceptAs<String> {
    override fun value(): String = rawValue
}
