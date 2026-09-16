// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.identity.IdentityDetails
import io.cratis.arc.identity.IdentityDetailsProvider
import io.cratis.arc.identity.IdentityProviderContext

public data class IdentityAddress(public val city: String)
public data class KotlinIdentityDetails(public val address: IdentityAddress)
public data class KotlinFactoryIdentityDetails(public val source: String)

public abstract class IdentityTemplate<T : Any> : IdentityDetailsProvider<T>
public abstract class IdentityBridge<T : Any> : IdentityTemplate<T>()

public class KotlinIdentityProvider : IdentityBridge<KotlinIdentityDetails>() {
    override val detailsType: Class<KotlinIdentityDetails> = KotlinIdentityDetails::class.java
    override suspend fun provide(context: IdentityProviderContext): IdentityDetails<KotlinIdentityDetails> =
        IdentityDetails(true, KotlinIdentityDetails(IdentityAddress(context.id)))
}

public fun identityFactory(): IdentityDetailsProvider<KotlinFactoryIdentityDetails> =
    object : IdentityDetailsProvider<KotlinFactoryIdentityDetails> {
        override val detailsType: Class<KotlinFactoryIdentityDetails> = KotlinFactoryIdentityDetails::class.java
        override suspend fun provide(context: IdentityProviderContext): IdentityDetails<KotlinFactoryIdentityDetails> =
            IdentityDetails(true, KotlinFactoryIdentityDetails(context.id))
    }
