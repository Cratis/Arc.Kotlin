// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.identity.IdentityDetails
import io.cratis.arc.identity.IdentityDetailsProvider
import io.cratis.arc.identity.IdentityProviderContext

/**
 * Identity details exposed by the standalone sample.
 *
 * No command or query references this type. KSP discovers it from the declared type argument of
 * [SampleIdentityDetailsProvider], so no explicit export annotation is needed and the generated
 * client still gets `SampleIdentityDetails.ts`.
 *
 * @property source Names the application that produced the details.
 */
public data class SampleIdentityDetails(public val source: String)

/** Supplies application-specific details for whichever principal authenticated. */
public class SampleIdentityDetailsProvider : IdentityDetailsProvider<SampleIdentityDetails> {
    override val detailsType: Class<SampleIdentityDetails> = SampleIdentityDetails::class.java

    override suspend fun provide(context: IdentityProviderContext): IdentityDetails<SampleIdentityDetails> =
        IdentityDetails(
            isUserAuthorized = true,
            details = SampleIdentityDetails("Arc.Kotlin sample")
        )
}
