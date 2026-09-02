// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:JvmName("IdentityDetailsProviders")

package io.cratis.arc.identity

import io.cratis.arc.commands.await

/** Adapts a Java-friendly [AsyncIdentityDetailsProvider] to the coroutine-first provider contract. */
public class AsyncIdentityDetailsProviderAdapter<T : Any>(
    private val provider: AsyncIdentityDetailsProvider<T>
) : IdentityDetailsProvider<T> {
    override val detailsType: Class<T>
        get() = provider.detailsType

    override suspend fun provide(context: IdentityProviderContext): IdentityDetails<T> =
        provider.provide(context).await()
}

/** Adapts this Java-friendly provider to the coroutine-first provider contract. */
public fun <T : Any> AsyncIdentityDetailsProvider<T>.asIdentityDetailsProvider(): IdentityDetailsProvider<T> =
    AsyncIdentityDetailsProviderAdapter(this)
