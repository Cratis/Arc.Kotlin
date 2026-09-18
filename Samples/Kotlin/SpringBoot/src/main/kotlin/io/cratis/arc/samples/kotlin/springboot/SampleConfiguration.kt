// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.commands.CommandFilter
import io.cratis.arc.identity.IdentityDetailsProvider
import io.cratis.arc.springboot.ArcPrincipalFactory
import io.cratis.arc.queries.QueryFilter
import io.cratis.arc.samples.kotlin.springboot.features.SampleTicker
import io.cratis.arc.samples.kotlin.springboot.features.crosscuttingauthorization.CrossCuttingAuthorizationCommandFilter
import io.cratis.arc.samples.kotlin.springboot.features.crosscuttingauthorization.CrossCuttingAuthorizationQueryFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

/** Wires the showcase features, identity and cross-cutting filters into the sample host. */
@Configuration(proxyBeanMethods = false)
public class SampleConfiguration {
    /** One daemon scheduler shared by every feature that publishes on a timer. */
    @Bean(destroyMethod = "destroy")
    public fun sampleTicker(): SampleTicker = SampleTicker()

    /**
     * Captures the caller the frontend's sign-in toggle describes.
     *
     * With `cratis.arc.samples.default-identity=false` a request that carries no client principal
     * stays anonymous, which is the mode the Authentication Queries page is built to show.
     */
    @Bean
    public fun sampleArcPrincipalFactory(
        objectMapper: ObjectMapper,
        @Value("\${cratis.arc.samples.default-identity:true}") defaultIdentity: Boolean
    ): ArcPrincipalFactory = SampleArcPrincipalFactory(objectMapper, defaultIdentity)

    /** Supplies the application-specific identity details served from `/.cratis/me`. */
    @Bean
    public fun sampleIdentityDetailsProvider(): IdentityDetailsProvider<SampleIdentityDetails> =
        SampleIdentityDetailsProvider()

    /** Requires a role for every command in the cross-cutting authorization feature. */
    @Bean
    public fun crossCuttingAuthorizationCommandFilter(): CommandFilter = CrossCuttingAuthorizationCommandFilter()

    /** Requires the same role for every query in that feature. */
    @Bean
    public fun crossCuttingAuthorizationQueryFilter(): QueryFilter = CrossCuttingAuthorizationQueryFilter()
}
