// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:JvmName("TenantsProviders")

package io.cratis.arc.tenancy

import io.cratis.arc.commands.await
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Supplies tenants exposed by development tooling. */
public fun interface TenantsProvider {
    /** Provides tenants in stable provider-defined order. */
    public suspend fun provide(): List<Tenant>
}

/** Java-friendly asynchronous development tenants provider. */
public fun interface AsyncTenantsProvider {
    /** Provides tenants without blocking the caller. */
    public fun provide(): CompletionStage<List<Tenant>>
}

/** Adapts a Java [AsyncTenantsProvider] to the coroutine-first [TenantsProvider] contract. */
public class AsyncTenantsProviderAdapter(
    private val provider: AsyncTenantsProvider
) : TenantsProvider {
    override suspend fun provide(): List<Tenant> = provider.provide().await()
}

/** Adapts this Java asynchronous provider to [TenantsProvider]. */
public fun AsyncTenantsProvider.asTenantsProvider(): TenantsProvider = AsyncTenantsProviderAdapter(this)

/**
 * Aggregates providers in declaration order and keeps the first tenant for each tenant identifier.
 *
 * Provider order, then each provider's item order, defines the result order. Provider failures propagate.
 */
public class TenantsProviderAggregator(providers: List<TenantsProvider>) : TenantsProvider {
    /** Immutable providers in precedence order. */
    public val providers: List<TenantsProvider> = java.util.List.copyOf(providers)

    override suspend fun provide(): List<Tenant> {
        val tenants = LinkedHashMap<TenantId, Tenant>()
        providers.forEach { provider ->
            provider.provide().forEach { tenant -> tenants.putIfAbsent(tenant.id, tenant) }
        }
        return java.util.List.copyOf(tenants.values)
    }

    /** Java asynchronous bridge using a caller-owned [coroutineScope]. */
    public fun provideAsync(coroutineScope: CoroutineScope): CompletionStage<List<Tenant>> =
        launchStage(coroutineScope) { provide() }

    /** Java blocking bridge for development and test setup. */
    public fun provideBlocking(): List<Tenant> = runBlocking { provide() }
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
