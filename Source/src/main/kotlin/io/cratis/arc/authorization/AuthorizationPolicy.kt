// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authorization

/** Host-neutral asynchronous named authorization policy. */
public fun interface AuthorizationPolicy {
    /** Evaluates [principal] and returns a deterministic authorization decision. */
    public suspend fun evaluate(principal: ArcPrincipal): AuthorizationResult
}
