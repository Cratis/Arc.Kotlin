// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.identity

import java.util.Collections

/** Complete identity payload returned to an Arc client. */
public class IdentityProviderResult<T : Any>(
    /** Stable identity identifier. */
    public val id: String,
    /** Display name for the identity. */
    public val name: String,
    /** Whether the host authenticated the identity. */
    @get:JvmName("isAuthenticated")
    public val isAuthenticated: Boolean,
    /** Whether the identity details provider authorized the identity. */
    @get:JvmName("isAuthorized")
    public val isAuthorized: Boolean,
    roles: List<String>,
    /** Application-specific identity details. */
    public val details: T
) {
    /** Roles captured from the host identity system, preserving their order. */
    public val roles: List<String> = Collections.unmodifiableList(ArrayList(roles))
}
