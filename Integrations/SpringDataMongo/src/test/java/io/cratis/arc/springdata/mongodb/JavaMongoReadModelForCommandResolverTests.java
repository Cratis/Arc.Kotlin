// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandHandler;
import io.cratis.arc.commands.CommandHandlerRegistry;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.queries.BlockingReadModelForCommandResolver;
import io.cratis.arc.queries.ReadModelForCommandOwnership;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Runtime coverage for the Java-facing marker, provider, and legacy resolver APIs. */
final class JavaMongoReadModelForCommandResolverTests {
    private static final ServiceResolver NO_SERVICES = new ServiceResolver() {
        @Override
        public <T> T resolve(Class<T> type) {
            return null;
        }
    };

    @Test
    void markerAndOwnershipAwareProviderAreOrdinaryJavaApis() {
        MongoOperations tenantA = mock(MongoOperations.class);
        MongoOperations tenantB = mock(MongoOperations.class);
        MongoTaskReadModel expected = new MongoTaskReadModel("same-id", "tenant-a");
        when(tenantA.findById("same-id", MongoTaskReadModel.class)).thenReturn(expected);
        TenantAwareMongoOperationsResolver operationsResolver = JavaMongoApiAccess.tenantAware(tenantA, tenantB);
        MongoReadModelForCommandResolver provider = JavaMongoApiAccess.provider(
            mappingContext(), operationsResolver, true);
        BlockingReadModelForCommandResolver blocking = JavaMongoApiAccess.asBlocking(provider);

        assertEquals(Set.of(MongoTaskReadModel.class), blocking.readModelTypes());
        assertEquals(ReadModelForCommandOwnership.FALLBACK, blocking.ownership());
        assertSame(
            expected,
            blocking.resolveBlocking(
                MongoTaskReadModel.class,
                commandContext("tenant-a"),
                "same-id"));
    }

    @Test
    void legacyConstructorAndResolveMethodRemainExecutableFromJava() {
        MongoOperations operations = mock(MongoOperations.class);
        CommandHandlerRegistry handlers = mock(CommandHandlerRegistry.class);
        CommandHandler handler = mock(CommandHandler.class);
        Object command = new Object();
        MongoTaskReadModel expected = new MongoTaskReadModel("same-id", "legacy");
        when(handlers.find(Object.class)).thenReturn(handler);
        when(handler.resolveCommandKey(command)).thenReturn("same-id");
        when(operations.findById("same-id", MongoTaskReadModel.class)).thenReturn(expected);
        DefaultMongoCommandReadModelResolver legacy = JavaMongoApiAccess.legacy(
            operations, mappingContext(), handlers);

        assertSame(expected, JavaMongoApiAccess.resolve(legacy, command));
    }

    private static MongoMappingContext mappingContext() {
        MongoMappingContext context = new MongoMappingContext();
        context.setInitialEntitySet(Set.of(MongoTaskReadModel.class));
        context.afterPropertiesSet();
        return context;
    }

    private static CommandContext commandContext(String tenantId) {
        Object command = new Object();
        return new CommandContext(
            UUID.randomUUID(),
            command,
            Object.class,
            ArcPrincipal.anonymous(),
            tenantId,
            null,
            null,
            NO_SERVICES);
    }
}
