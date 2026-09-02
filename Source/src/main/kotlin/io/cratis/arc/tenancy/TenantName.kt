// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.tenancy

import io.cratis.arc.concepts.ConceptAs

/** Strongly typed display name for a development tenant. */
public data class TenantName(private val rawValue: String) : ConceptAs<String> {
    override fun value(): String = rawValue

    override fun toString(): String = rawValue

    public companion object {
        /** Empty tenant name. */
        @JvmField
        public val EMPTY: TenantName = TenantName("")

        /** Creates a tenant name from its wire [value]. */
        @JvmStatic
        public fun of(value: String): TenantName = TenantName(value)
    }
}
