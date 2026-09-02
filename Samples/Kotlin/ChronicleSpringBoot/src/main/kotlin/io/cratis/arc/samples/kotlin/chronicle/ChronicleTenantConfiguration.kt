// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.chronicle

import io.cratis.arc.chronicle.TenantEventStoreProvider
import io.cratis.arc.chronicle.TenantEventStoreResolver
import io.cratis.chronicle.ChronicleOptions
import io.cratis.chronicle.IChronicleClient
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.artifacts.IArtifactActivator
import io.cratis.chronicle.artifacts.KnownClientArtifacts
import io.cratis.chronicle.connection.ChronicleConnectionString
import io.cratis.chronicle.sinks.WellKnownSinkTypes
import io.cratis.chronicle.spring.ChronicleProperties
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Bridges Arc's explicitly captured tenant namespace to the released Chronicle client. */
@Configuration(proxyBeanMethods = false)
public class ChronicleTenantConfiguration {
    /** Uses an explicit artifact list so executable Spring Boot jars register the same domain contract. */
    @Bean
    public fun chronicleOptions(
        properties: ChronicleProperties,
        artifactActivator: IArtifactActivator,
        @Value("\${spring.application.name:Unknown}") applicationName: String
    ): ChronicleOptions = ChronicleOptions(
        connectionString = ChronicleConnectionString.parse(properties.connectionString),
        programIdentifier = properties.programIdentifier ?: applicationName,
        defaultSinkTypeId = properties.defaultSinkTypeId
            ?: System.getenv("CHRONICLE_SINK_TYPE")
            ?: WellKnownSinkTypes.MONGODB,
        autoDiscoverAndRegister = properties.autoDiscoverAndRegister,
        artifacts = KnownClientArtifacts(
            TaskCreated::class,
            TaskRenamed::class,
            TaskView::class,
            TaskViewReducer::class
        ),
        artifactActivator = artifactActivator
    )

    /** Resolves the configured event-store name in exactly the requested namespace. */
    @Bean
    public fun tenantEventStoreProvider(
        client: IChronicleClient,
        properties: ChronicleProperties
    ): TenantEventStoreProvider {
        val stores = ConcurrentHashMap<String, IEventStore>()
        return TenantEventStoreProvider { namespace ->
            stores.computeIfAbsent(namespace) { tenantNamespace ->
                client.getEventStore(properties.eventStore, tenantNamespace).also { eventStore ->
                    runBlocking { eventStore.registerAll() }
                }
            }
        }
    }

    /** Fails closed when Arc did not capture a tenant namespace. */
    @Bean
    public fun tenantEventStoreResolver(provider: TenantEventStoreProvider): TenantEventStoreResolver =
        TenantEventStoreResolver { namespace -> namespace?.let(provider::provide) }

    /** Supplies generated queries with explicit namespace-aware Chronicle reads. */
    @Bean
    public fun taskViewReader(resolver: TenantEventStoreResolver): TaskViewReader = ChronicleTaskViewReader(resolver)
}
