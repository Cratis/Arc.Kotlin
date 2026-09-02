// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javachronicle;

import io.cratis.arc.chronicle.TenantEventStoreProvider;
import io.cratis.arc.chronicle.TenantEventStoreResolver;
import io.cratis.chronicle.ChronicleOptions;
import io.cratis.chronicle.IChronicleClient;
import io.cratis.chronicle.IEventStore;
import io.cratis.chronicle.artifacts.IArtifactActivator;
import io.cratis.chronicle.artifacts.KnownClientArtifacts;
import io.cratis.chronicle.connection.ChronicleConnectionString;
import io.cratis.chronicle.java.EventStoreJavaBridge;
import io.cratis.chronicle.sinks.WellKnownSinkTypes;
import io.cratis.chronicle.spring.ChronicleProperties;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import kotlin.jvm.JvmClassMappingKt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bridges Arc's explicitly captured tenant namespace to the released Chronicle client. */
@Configuration(proxyBeanMethods = false)
public class ChronicleTenantConfiguration {
    /** Uses an explicit artifact list so executable Spring Boot jars register the same domain contract. */
    @Bean
    public ChronicleOptions chronicleOptions(
        ChronicleProperties properties,
        IArtifactActivator artifactActivator,
        @Value("${spring.application.name:Unknown}") String applicationName
    ) {
        var artifacts = new KnownClientArtifacts(List.of(
            JvmClassMappingKt.getKotlinClass(TaskCreated.class),
            JvmClassMappingKt.getKotlinClass(TaskRenamed.class),
            JvmClassMappingKt.getKotlinClass(TaskView.class),
            JvmClassMappingKt.getKotlinClass(TaskViewReducer.class)));
        var sinkType = properties.getDefaultSinkTypeId();
        if (sinkType == null) sinkType = System.getenv("CHRONICLE_SINK_TYPE");
        if (sinkType == null) sinkType = WellKnownSinkTypes.MONGODB;
        var programIdentifier = properties.getProgramIdentifier() == null
            ? applicationName
            : properties.getProgramIdentifier();
        return new ChronicleOptions(
            ChronicleConnectionString.Companion.parse(properties.getConnectionString()),
            programIdentifier,
            sinkType,
            properties.getAutoDiscoverAndRegister(),
            artifacts,
            artifactActivator);
    }

    /** Resolves the configured event-store name in exactly the requested namespace. */
    @Bean
    public TenantEventStoreProvider tenantEventStoreProvider(
        IChronicleClient client,
        ChronicleProperties properties
    ) {
        var stores = new ConcurrentHashMap<String, IEventStore>();
        return namespace -> stores.computeIfAbsent(namespace, tenantNamespace -> {
            var eventStore = client.getEventStore(properties.getEventStore(), tenantNamespace);
            EventStoreJavaBridge.registerAll(eventStore);
            return eventStore;
        });
    }

    /** Fails closed when Arc did not capture a tenant namespace. */
    @Bean
    public TenantEventStoreResolver tenantEventStoreResolver(TenantEventStoreProvider provider) {
        return namespace -> namespace == null ? null : provider.provide(namespace);
    }

    /** Supplies generated queries with explicit namespace-aware Chronicle reads. */
    @Bean
    public TaskViewReader taskViewReader(TenantEventStoreResolver resolver) {
        return new ChronicleTaskViewReader(resolver);
    }
}
