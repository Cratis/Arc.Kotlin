// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.identity

import io.cratis.arc.authorization.ArcPrincipal

/** Immutable development user and optional application-specific details. */
public data class User @JvmOverloads constructor(
    /** Host-neutral identity used when selecting the development user. */
    public val principal: ArcPrincipal,
    /** Optional application-specific identity details. */
    public val details: Any? = null
)
