// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authorization

import io.cratis.arc.identity.IdentityClaim
import java.util.Collections
import java.util.LinkedHashSet

/** Immutable identity information supplied explicitly for a command execution. */
public class ArcPrincipal @JvmOverloads constructor(
    /** Name supplied by the host identity system, when available. */
    public val name: String? = null,
    /** Whether the identity has been authenticated. */
    @get:JvmName("isAuthenticated")
    public val isAuthenticated: Boolean = false,
    roles: Set<String> = emptySet(),
    /** Stable identity identifier. */
    public val id: String = name ?: UNKNOWN_IDENTITY,
    claims: List<IdentityClaim> = emptyList(),
    /** Authentication mechanism captured by the host, when it can be determined safely. */
    public val authenticationScheme: String? = null
) {
    /** Roles assigned to this identity, preserving the host's order. */
    public val roles: Set<String> = Collections.unmodifiableSet(LinkedHashSet(roles))

    /** Claims supplied by the host identity system, preserving their order. */
    public val claims: List<IdentityClaim> = Collections.unmodifiableList(ArrayList(claims))

    /** Returns whether the identity belongs to [role]. */
    public fun isInRole(role: String): Boolean = roles.contains(role)

    public companion object {
        private const val UNKNOWN_IDENTITY = "unknown"

        /** Creates an unauthenticated identity. */
        @JvmStatic
        public fun anonymous(): ArcPrincipal = ArcPrincipal()
    }
}
