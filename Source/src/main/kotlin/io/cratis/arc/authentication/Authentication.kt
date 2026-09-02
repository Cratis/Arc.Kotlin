// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.authentication

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Host-neutral authentication service. */
public interface Authentication {
    /** Whether any handlers are configured. */
    public val hasHandlers: Boolean

    /** Authenticates [context]. */
    public suspend fun handleAuthentication(context: AuthenticationRequestContext): AuthenticationResult
}

/** Ordered handler chain. The first success wins; otherwise all failures are retained in handler order. */
public class DefaultAuthentication(handlers: List<AuthenticationHandler>) : Authentication {
    private val handlers: List<AuthenticationHandler> = java.util.List.copyOf(handlers)

    override val hasHandlers: Boolean
        get() = handlers.isNotEmpty()

    override suspend fun handleAuthentication(context: AuthenticationRequestContext): AuthenticationResult {
        val failures = mutableListOf<AuthenticationFailureReason>()
        handlers.forEach { handler ->
            val result = handler.handleAuthentication(context)
            if (result.isAuthenticated) return result
            result.failure?.reasons?.let(failures::addAll)
        }
        return if (failures.isEmpty()) AuthenticationResult.ANONYMOUS else AuthenticationResult.failed(failures)
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
    public fun handleAuthentication(context: AuthenticationRequestContext): CompletionStage<AuthenticationResult> {
        val future = CompletableFuture<AuthenticationResult>()
        lateinit var job: Job
        job = coroutineScope.launch {
            try {
                future.complete(authentication.handleAuthentication(context))
            } catch (exception: CancellationException) {
                future.cancel(false)
                throw exception
            } catch (exception: Exception) {
                future.completeExceptionally(exception)
            }
        }
        future.whenComplete { _, _ -> if (future.isCancelled) job.cancel() }
        job.invokeOnCompletion { cause ->
            if (cause != null && !future.isDone) {
                if (cause is CancellationException) future.cancel(false) else future.completeExceptionally(cause)
            }
        }
        return future
    }
}
