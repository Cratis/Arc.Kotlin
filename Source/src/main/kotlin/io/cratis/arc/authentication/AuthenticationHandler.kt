// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authentication

import io.cratis.arc.commands.await
import java.util.concurrent.CompletionStage

/** Coroutine-first host-neutral request authentication handler. */
public fun interface AuthenticationHandler {
    /** Authenticates [context], returning anonymous when this handler does not recognize it. */
    public suspend fun handleAuthentication(context: AuthenticationRequestContext): AuthenticationResult
}

/** Java-friendly asynchronous authentication handler. */
public fun interface AsyncAuthenticationHandler {
    /** Authenticates [context] without blocking the request thread. */
    public fun handleAuthentication(context: AuthenticationRequestContext): CompletionStage<AuthenticationResult>
}

/** Adapts a Java [AsyncAuthenticationHandler] to the coroutine-first contract. */
public class AsyncAuthenticationHandlerAdapter(
    private val handler: AsyncAuthenticationHandler
) : AuthenticationHandler {
    override suspend fun handleAuthentication(context: AuthenticationRequestContext): AuthenticationResult =
        handler.handleAuthentication(context).await()
}

/** Adapts this Java-friendly handler to the coroutine-first contract. */
public fun AsyncAuthenticationHandler.asAuthenticationHandler(): AuthenticationHandler =
    AsyncAuthenticationHandlerAdapter(this)
