// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.identity.IdentityDetails
import io.cratis.arc.identity.IdentityDetailsProvider
import io.cratis.arc.identity.IdentityProviderContext
import io.cratis.arc.springboot.ArcPrincipalFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Identity details exposed by the standalone sample. */
public data class SampleIdentityDetails(public val source: String)

/** Provides deterministic identity behavior for the standalone sample host. */
@Configuration(proxyBeanMethods = false)
public class SampleIdentityConfiguration {
    /** Supplies an authenticated sample principal without requiring an external identity provider. */
    @Bean
    public fun sampleArcPrincipalFactory(): ArcPrincipalFactory = ArcPrincipalFactory { _, _ ->
        ArcPrincipal(
            name = SAMPLE_USER_NAME,
            isAuthenticated = true,
            roles = setOf(SAMPLE_ROLE),
            id = SAMPLE_USER_ID
        )
    }

    /** Supplies application-specific details for the sample principal. */
    @Bean
    public fun sampleIdentityDetailsProvider(): IdentityDetailsProvider<SampleIdentityDetails> =
        object : IdentityDetailsProvider<SampleIdentityDetails> {
            override val detailsType: Class<SampleIdentityDetails> = SampleIdentityDetails::class.java

            override suspend fun provide(context: IdentityProviderContext): IdentityDetails<SampleIdentityDetails> =
                IdentityDetails(
                    isUserAuthorized = context.id == SAMPLE_USER_ID,
                    details = SampleIdentityDetails("Arc.Kotlin sample")
                )
        }

    private companion object {
        const val SAMPLE_USER_ID = "arc-kotlin-runtime-gate"
        const val SAMPLE_USER_NAME = "Arc Kotlin Runtime Gate"
        const val SAMPLE_ROLE = "sample"
    }
}
