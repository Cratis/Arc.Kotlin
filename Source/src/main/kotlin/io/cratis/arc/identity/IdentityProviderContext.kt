// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.identity

import java.util.Collections

/** Immutable identity information passed explicitly to an identity details provider. */
public class IdentityProviderContext(
    /** Stable identity identifier. */
    public val id: String,
    /** Display name for the identity. */
    public val name: String,
    claims: List<IdentityClaim>
) {
    /** Claims captured from the host identity system, preserving their order. */
    public val claims: List<IdentityClaim> = Collections.unmodifiableList(ArrayList(claims))
}
