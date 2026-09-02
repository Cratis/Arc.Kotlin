// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:JvmName("UsersProviders")

package io.cratis.arc.identity

import io.cratis.arc.commands.await
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Supplies users exposed by development tooling. */
public fun interface UsersProvider {
    /** Provides users in stable provider-defined order. */
    public suspend fun provide(): List<User>
}

/** Java-friendly asynchronous development users provider. */
public fun interface AsyncUsersProvider {
    /** Provides users without blocking the caller. */
    public fun provide(): CompletionStage<List<User>>
}

/** Adapts a Java [AsyncUsersProvider] to the coroutine-first [UsersProvider] contract. */
public class AsyncUsersProviderAdapter(
    private val provider: AsyncUsersProvider
) : UsersProvider {
    override suspend fun provide(): List<User> = provider.provide().await()
}

/** Adapts this Java asynchronous provider to [UsersProvider]. */
public fun AsyncUsersProvider.asUsersProvider(): UsersProvider = AsyncUsersProviderAdapter(this)

/**
 * Aggregates providers in declaration order and keeps the first user for each principal identifier.
 *
 * Provider order, then each provider's item order, defines the result order. Provider failures propagate.
 */
public class UsersProviderAggregator(providers: List<UsersProvider>) : UsersProvider {
    /** Immutable providers in precedence order. */
    public val providers: List<UsersProvider> = java.util.List.copyOf(providers)

    override suspend fun provide(): List<User> {
        val users = LinkedHashMap<String, User>()
        providers.forEach { provider ->
            provider.provide().forEach { user -> users.putIfAbsent(user.principal.id, user) }
        }
        return java.util.List.copyOf(users.values)
    }

    /** Java asynchronous bridge using a caller-owned [coroutineScope]. */
    public fun provideAsync(coroutineScope: CoroutineScope): CompletionStage<List<User>> =
        launchStage(coroutineScope) { provide() }

    /** Java blocking bridge for development and test setup. */
    public fun provideBlocking(): List<User> = runBlocking { provide() }
}

private fun <T> launchStage(coroutineScope: CoroutineScope, operation: suspend () -> T): CompletionStage<T> {
    val future = CompletableFuture<T>()
    lateinit var job: Job
    job = coroutineScope.launch {
        try {
            future.complete(operation())
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
