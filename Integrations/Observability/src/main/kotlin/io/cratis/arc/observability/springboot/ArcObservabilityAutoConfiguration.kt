// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.observability.springboot

import io.cratis.arc.authentication.Authentication
import io.cratis.arc.commands.CommandPipeline
import io.cratis.arc.identity.AsyncIdentityDetailsProvider
import io.cratis.arc.identity.IdentityDetailsProvider
import io.cratis.arc.queries.ObservableQueryPipeline
import io.cratis.arc.queries.QueryPipeline
import io.cratis.arc.springboot.ArcAutoConfiguration
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/** Decorates Arc execution contracts with Micrometer observations when an observation registry is available. */
@AutoConfiguration(after = [ArcAutoConfiguration::class])
@ConditionalOnClass(ObservationRegistry::class)
@ConditionalOnBean(ObservationRegistry::class)
@ConditionalOnProperty(prefix = "cratis.arc.observability", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ArcObservabilityProperties::class)
public class ArcObservabilityAutoConfiguration {
    /** Adds observations without replacing application or Arc pipeline beans. */
    @Bean
    public fun arcObservabilityBeanPostProcessor(
        registry: ObservationRegistry,
        properties: ArcObservabilityProperties
    ): BeanPostProcessor = ArcObservabilityBeanPostProcessor(registry, properties)
}

private class ArcObservabilityBeanPostProcessor(
    private val registry: ObservationRegistry,
    properties: ArcObservabilityProperties
) : BeanPostProcessor {
    private val recorder = ArcObservationRecorder(registry, properties)

    @Suppress("UNCHECKED_CAST")
    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (registry === ObservationRegistry.NOOP || bean is ArcObservedComponent) return bean
        return when (bean) {
            is CommandPipeline -> ObservedCommandPipeline(bean, recorder)
            is QueryPipeline -> ObservedQueryPipeline(bean, recorder)
            is ObservableQueryPipeline -> ObservedObservableQueryPipeline(bean, recorder)
            is Authentication -> ObservedAuthentication(bean, recorder)
            is IdentityDetailsProvider<*> -> ObservedIdentityDetailsProvider(
                bean as IdentityDetailsProvider<Any>,
                recorder
            )
            is AsyncIdentityDetailsProvider<*> -> ObservedAsyncIdentityDetailsProvider(
                bean as AsyncIdentityDetailsProvider<Any>,
                registry
            )
            else -> bean
        }
    }
}
