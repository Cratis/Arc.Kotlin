// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authentication

import io.cratis.arc.java.launchStage
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CoroutineScope

/** Host-neutral authentication service. */
public interface Authentication {
    /** Whether any handlers are configured. */
    public val hasHandlers: Boolean

    /** Authenticates [context]. */
    public suspend fun handleAuthentication(context: AuthenticationRequestContext): AuthenticationResult
}

/**
 * Ordered handler chain in which the first handler that recognizes the request decides the outcome.
 *
 * A handler recognizes a request by either supplying a principal or rejecting the credentials. Both outcomes are
 * terminal: no later handler runs, so a rejection can never be overridden by a later success.
 * [AuthenticationResult.ANONYMOUS] is returned only when no handler recognized the request.
 */
public class DefaultAuthentication(handlers: List<AuthenticationHandler>) : Authentication {
    private val handlers: List<AuthenticationHandler> = java.util.List.copyOf(handlers)

    override val hasHandlers: Boolean
        get() = handlers.isNotEmpty()

    override suspend fun handleAuthentication(context: AuthenticationRequestContext): AuthenticationResult {
        handlers.forEach { handler ->
            val result = handler.handleAuthentication(context)
            if (result.isAuthenticated || result.failure != null) return result
        }
        return AuthenticationResult.ANONYMOUS
    }
}

/** Java-friendly `CompletionStage` adapter for [Authentication]. */
public class AsyncAuthentication internal constructor(
    private val authentication: Authentication,
    private val coroutineScope: CoroutineScope
) {
    public companion object {
        /** Kotlin host-integration factory; Java callers should use JavaAsyncScope. */
        @JvmStatic
        @JvmSynthetic
        public fun fromCoroutineScope(
            authentication: Authentication,
            coroutineScope: CoroutineScope
        ): AsyncAuthentication = AsyncAuthentication(authentication, coroutineScope)
    }

    /** Authenticates [context] and propagates cancellation in both directions. */
    public fun handleAuthentication(context: AuthenticationRequestContext): CompletionStage<AuthenticationResult> =
        coroutineScope.launchStage { authentication.handleAuthentication(context) }
}
