// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.tenancy

import io.cratis.arc.concepts.ConceptAs

/** Strongly typed tenant identifier. */
public data class TenantId(private val rawValue: String) : ConceptAs<String> {
    /** Returns the tenant identifier's wire value. */
    override fun value(): String = rawValue

    /** Whether this identifier addresses the default tenant. */
    @get:JvmName("isDefault")
    public val isDefault: Boolean
        get() = this == NOT_SET || this == DEFAULT

    /** Returns the wire value for diagnostics and configuration. */
    override fun toString(): String = rawValue

    public companion object {
        /** Tenant identifier used when no tenant has been selected. */
        @JvmField
        public val NOT_SET: TenantId = TenantId("[NotSet]")

        /** Default tenant identifier. */
        @JvmField
        public val DEFAULT: TenantId = TenantId("Default")

        /** Default fixed identifier used by development tenancy. */
        @JvmField
        public val DEVELOPMENT: TenantId = TenantId("development")

        /** Creates an identifier from its wire [value]. */
        @JvmStatic
        public fun of(value: String): TenantId = TenantId(value)
    }
}
