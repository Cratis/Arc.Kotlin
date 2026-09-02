// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.identity

/** A single immutable claim supplied by the host identity system. */
public data class IdentityClaim(
    /** Claim type. */
    public val type: String,
    /** Claim value. */
    public val value: String
)
