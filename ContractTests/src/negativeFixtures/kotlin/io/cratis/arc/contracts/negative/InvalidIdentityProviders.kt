// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.identity.IdentityDetailsProvider

public class GenericIdentityProvider<T : Any> : IdentityDetailsProvider<T> {
    override val detailsType: Class<T> get() = error("fixture")
    override suspend fun provide(context: io.cratis.arc.identity.IdentityProviderContext): io.cratis.arc.identity.IdentityDetails<T> = error("fixture")
}

public fun <P : IdentityDetailsProvider<UnsupportedIdentityDetails>> genericProviderReturn(): P = error("fixture")
public sealed class SealedIdentityDetails(val value: String)
public fun sealedIdentity(): IdentityDetailsProvider<SealedIdentityDetails> = error("fixture")
public fun starredIdentity(): IdentityDetailsProvider<*> = error("fixture")
public fun erasedIdentity(): IdentityDetailsProvider<Any> = error("fixture")
public fun <T : Any> genericIdentity(): IdentityDetailsProvider<T> = error("fixture")
internal class HiddenIdentityDetails(val value: String)
public fun hiddenIdentity(): IdentityDetailsProvider<HiddenIdentityDetails> = error("fixture")
public data class UnsupportedIdentityDetails(val values: List<String?>)
public fun unsupportedIdentity(): IdentityDetailsProvider<UnsupportedIdentityDetails> = error("fixture")
