// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.identity

/** Details and authorization decision returned by an [IdentityDetailsProvider]. */
public data class IdentityDetails<T : Any>(
    /** Whether the authenticated user is authorized to use the application. */
    @get:JvmName("isUserAuthorized")
    public val isUserAuthorized: Boolean,
    /** Application-specific identity details. */
    public val details: T
)
