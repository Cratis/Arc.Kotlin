// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authorization

import java.util.concurrent.ConcurrentHashMap

/** Thread-safe, duplicate-rejecting authorization policy registry. */
public class ConcurrentAuthorizationPolicyRegistry : AuthorizationPolicyRegistry {
    private val policies = ConcurrentHashMap<String, AuthorizationPolicy>()

    override fun register(name: String, policy: AuthorizationPolicy) {
        require(name.isNotBlank()) { "Policy name cannot be blank." }
        if (policies.putIfAbsent(name, policy) != null) throw DuplicateAuthorizationPolicyException(name)
    }

    override fun find(name: String): AuthorizationPolicy? = policies[name]
}
