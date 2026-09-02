// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.authorization.AuthorizationPolicy
import io.cratis.arc.authorization.AuthorizationResult
import io.cratis.arc.commands.await
import java.util.concurrent.CompletionStage

/** Synchronous Java implementation surface for a named authorization policy. */
public fun interface BlockingAuthorizationPolicy {
    /** Evaluates a principal without coroutine types in the signature. */
    public fun evaluate(principal: ArcPrincipal): AuthorizationResult
}

/** CompletionStage-based Java implementation surface for a named authorization policy. */
public fun interface AsyncAuthorizationPolicy {
    /** Evaluates a principal asynchronously. */
    public fun evaluate(principal: ArcPrincipal): CompletionStage<AuthorizationResult>
}

/** Adapts a [BlockingAuthorizationPolicy] to Arc's suspending policy SPI. */
public class BlockingAuthorizationPolicyAdapter(private val policy: BlockingAuthorizationPolicy) : AuthorizationPolicy {
    override suspend fun evaluate(principal: ArcPrincipal): AuthorizationResult = policy.evaluate(principal)
}

/** Adapts an [AsyncAuthorizationPolicy] to Arc's suspending policy SPI. */
public class AsyncAuthorizationPolicyAdapter(private val policy: AsyncAuthorizationPolicy) : AuthorizationPolicy {
    override suspend fun evaluate(principal: ArcPrincipal): AuthorizationResult = policy.evaluate(principal).await()
}
