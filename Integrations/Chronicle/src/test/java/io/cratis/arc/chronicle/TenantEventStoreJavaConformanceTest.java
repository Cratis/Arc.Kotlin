// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle;

import io.cratis.arc.commands.CommandPipeline;
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.chronicle.IEventStore;
import io.cratis.chronicle.eventSequences.EventForEventSourceId;
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyScope;
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyScopeBuilder;
import io.cratis.chronicle.events.EventContext;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import kotlinx.coroutines.CoroutineScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantEventStoreJavaConformanceTest {
    @Test
    void resolverAndProviderAreJavaFunctionalInterfaces() {
        TenantEventStoreProvider provider = tenantNamespace -> null;
        TenantEventStoreResolver resolver = tenantNamespace ->
            tenantNamespace == null ? null : provider.provide(tenantNamespace);

        ChronicleCommandResponseValueHandler handler = new ChronicleCommandResponseValueHandler(
            resolver,
            new ConcurrentCommandHandlerRegistry()
        );

        assertNotNull(handler);
        assertNull(resolver.resolve(null));
        assertNull(provider.provide("tenant-one"));
    }

    @Test
    void scopedEventBuilderIsJavaFriendlyAndProducesImmutableSnapshots() {
        ConcurrencyScope scope = ConcurrencyScope.Companion.getNone();
        EventsWithConcurrencyScopes response = EventsWithConcurrencyScopes.builder()
            .event("source-one", new Object())
            .concurrencyScope("source-one", scope)
            .concurrencyScope(
                "independent",
                (Consumer<ConcurrencyScopeBuilder>) builder -> builder.withEventSourceId())
            .expectedSequenceNumber("exact", 7L)
            .build();

        assertEquals(1, response.getEvents().size());
        assertEquals("source-one", response.getEvents().get(0).getEventSourceId());
        assertEquals(scope, response.getConcurrencyScopes().get("source-one"));
        assertEquals(7L, response.expectedSequenceNumber("exact"));
        assertThrows(
            UnsupportedOperationException.class,
            () -> response.getEvents().add(new EventForEventSourceId("other", new Object())));
        assertThrows(
            UnsupportedOperationException.class,
            () -> response.getConcurrencyScopes().put("other", scope));
    }

    @Test
    void systemRoleAnnotationRetainsExactDeclaredRoles() {
        ExecuteCommandsAsSystem annotation = JavaSystemReactor.class.getAnnotation(ExecuteCommandsAsSystem.class);

        assertEquals(List.of("Administrator", "Auditor"), List.of(annotation.roles()));
    }

    @SuppressWarnings("unused")
    private ChronicleCommandResponseValueHandler singleStoreConstructor(
        IEventStore eventStore,
        ConcurrentCommandHandlerRegistry registry
    ) {
        ChronicleCommandTransaction transactions = new ChronicleCommandTransaction();
        ChronicleCommandExecutionScope scope = new ChronicleCommandExecutionScope(transactions);
        assertNotNull(scope);
        return new ChronicleCommandResponseValueHandler(eventStore, registry, transactions);
    }

    @SuppressWarnings("unused")
    private EventsWithConcurrencyScopesCommandResponseValueHandler scopedConstructor(
        IEventStore eventStore,
        ChronicleCommandTransaction transactions
    ) {
        return new EventsWithConcurrencyScopesCommandResponseValueHandler(eventStore, transactions);
    }

    @SuppressWarnings("unused")
    private CompletionStage<?> executeSideEffects(
        CommandPipeline pipeline,
        ConcurrentCommandHandlerRegistry registry,
        ServiceResolver services,
        CoroutineScope coroutineScope,
        EventContext eventContext
    ) {
        ChronicleCommandSideEffectHandler handler = new ChronicleCommandSideEffectHandler(
            pipeline,
            registry,
            services,
            coroutineScope);
        return handler.executeAsync(List.of(new Object()), JavaSystemReactor.class, eventContext);
    }

    @ExecuteCommandsAsSystem(roles = { "Administrator", "Auditor" })
    private static final class JavaSystemReactor {
    }
}
