// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

/** Immutable authorization metadata emitted for an Arc artifact. */
public class AuthorizationMetadata @JvmOverloads constructor(
    public val allowAnonymous: Boolean = false,
    public val policy: String? = null,
    roles: List<String> = emptyList(),
    schemes: List<String> = emptyList()
) {
    public val roles: List<String> = java.util.List.copyOf(roles)
    public val schemes: List<String> = java.util.List.copyOf(schemes)
}
