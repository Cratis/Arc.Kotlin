// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authorization

/** Registry of named host-neutral authorization policies. */
public interface AuthorizationPolicyRegistry {
    /** Registers [policy] under [name], rejecting duplicate names. */
    public fun register(name: String, policy: AuthorizationPolicy)

    /** Finds a registered policy, or null. */
    public fun find(name: String): AuthorizationPolicy?
}
