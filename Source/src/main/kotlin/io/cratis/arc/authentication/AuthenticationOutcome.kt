// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authentication

import io.cratis.arc.authorization.ArcPrincipal

/** Exhaustive Kotlin view of an [AuthenticationResult]. */
public sealed interface AuthenticationOutcome {
    /** Authentication succeeded with [principal]. */
    public data class Authenticated(public val principal: ArcPrincipal) : AuthenticationOutcome

    /** Configured authentication handlers rejected the request with [failure]. */
    public data class Failed(public val failure: AuthenticationFailure) : AuthenticationOutcome

    /** No authentication handler recognized the request. */
    public data object Anonymous : AuthenticationOutcome
}

/** Returns an exhaustive Kotlin outcome without changing the Java-friendly [AuthenticationResult] contract. */
@get:JvmSynthetic
public val AuthenticationResult.outcome: AuthenticationOutcome
    get() = when {
        principal != null -> AuthenticationOutcome.Authenticated(principal)
        failure != null -> AuthenticationOutcome.Failed(failure)
        else -> AuthenticationOutcome.Anonymous
    }
