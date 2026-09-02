// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authentication

import io.cratis.arc.concepts.ConceptAs

/** Host-neutral reason supplied by an authentication handler. */
public data class AuthenticationFailureReason(private val rawValue: String) : ConceptAs<String> {
    init {
        require(rawValue.isNotBlank()) { "Authentication failure reason cannot be blank." }
    }

    /** Returns the reason's wire value. */
    override fun value(): String = rawValue

    override fun toString(): String = rawValue

    public companion object {
        /** Creates a reason from its wire [value]. */
        @JvmStatic
        public fun of(value: String): AuthenticationFailureReason = AuthenticationFailureReason(value)
    }
}
