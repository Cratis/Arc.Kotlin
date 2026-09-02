// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandKeyProvider;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.queries.BlockingReadModelForCommandResolver;
import io.cratis.arc.queries.CanResolveReadModelForCommand;
import io.cratis.arc.queries.MultipleReadModelResolversForCommandException;
import io.cratis.arc.queries.ReadModelForCommandOwnership;
import io.cratis.arc.queries.ReadModelForCommandResolverRegistry;
import io.cratis.arc.queries.UnableToResolveReadModelFromCommandContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime conformance for Java implementations of command-side read-model resolvers. */
final class ReadModelForCommandResolversJavaConformanceTest {
    private static final ServiceResolver NO_SERVICES = new ServiceResolver() {
        @Override
        public <T> T resolve(Class<T> type) {
            return null;
        }
    };

    @Test
    void ordinaryAsyncResolverExposesClaimsAndReceivesCommandContextAndKey() {
        OrderView expected = new OrderView("order-42", "async");
        AsyncResolver resolver = new AsyncResolver(ReadModelForCommandOwnership.DECLARED, expected);
        CommandContext context = commandContext("tenant-one", "order-42");

        Object resolved = resolver.resolve(OrderView.class, context, "order-42").toCompletableFuture().join();

        assertEquals(Set.of(OrderView.class), resolver.readModelTypes());
        assertEquals(ReadModelForCommandOwnership.DECLARED, resolver.ownership());
        assertSame(expected, resolved);
        assertSame(context, resolver.context);
        assertSame(OrderView.class, resolver.requestedType);
        assertEquals("tenant-one", resolver.context.getTenantId());
        assertEquals("order-42", resolver.context.getCommandKey());
        assertEquals("order-42", resolver.key);
    }

    @Test
    void ordinaryBlockingResolverBridgesResolutionAndFailuresWithoutKotlinTypes() {
        OrderView expected = new OrderView("order-42", "blocking");
        BlockingResolver resolver = new BlockingResolver(ReadModelForCommandOwnership.FALLBACK, expected, null);
        CommandContext context = commandContext("tenant-one", "order-42");

        assertSame(expected, resolver.resolve(OrderView.class, context, "order-42").toCompletableFuture().join());
        assertEquals(Set.of(OrderView.class), resolver.readModelTypes());
        assertEquals(ReadModelForCommandOwnership.FALLBACK, resolver.ownership());
        assertEquals("tenant-one", resolver.context.getTenantId());
        assertEquals("order-42", resolver.key);

        IllegalStateException failure = new IllegalStateException("lookup failed");
        BlockingResolver failing = new BlockingResolver(ReadModelForCommandOwnership.DECLARED, null, failure);
        CompletionStage<Object> stage = assertDoesNotThrow(
            () -> failing.resolve(OrderView.class, context, "order-42"));
        CompletionException exception = assertThrows(
            CompletionException.class,
            () -> stage.toCompletableFuture().join());
        assertSame(failure, exception.getCause());
    }

    @Test
    void registryKeepsExactClaimsMissingKeysAndOwnershipArbitration() {
        BlockingResolver fallback = new BlockingResolver(
            ReadModelForCommandOwnership.FALLBACK,
            new OrderView("order-42", "fallback"),
            null);
        AsyncResolver declared = new AsyncResolver(
            ReadModelForCommandOwnership.DECLARED,
            new OrderView("order-42", "declared"));
        ReadModelForCommandResolverRegistry registry = new ReadModelForCommandResolverRegistry(
            List.of(fallback, declared));
        CommandContext context = commandContext("tenant-one", "order-42");

        assertEquals(Set.of(OrderView.class), registry.readModelTypes());
        assertTrue(registry.contains(OrderView.class));
        assertFalse(registry.contains(Object.class));
        assertEquals(
            new OrderView("order-42", "declared"),
            registry.resolveBlocking(OrderView.class, context));
        assertNull(registry.resolveBlocking(Object.class, context));

        CompletionException missingKey = assertThrows(
            CompletionException.class,
            () -> registry.resolveBlocking(OrderView.class, commandContext("tenant-one", null)));
        assertTrue(missingKey.getCause() instanceof UnableToResolveReadModelFromCommandContext);
    }

    @Test
    void registryAdaptsSynchronousNonBlockingFailuresAcrossJavaResolutionPaths() {
        Thread callerThread = Thread.currentThread();
        IllegalStateException failure = new IllegalStateException("lookup failed synchronously");
        ThrowingAsyncResolver resolver = new ThrowingAsyncResolver(failure);
        ReadModelForCommandResolverRegistry registry = new ReadModelForCommandResolverRegistry(List.of(resolver));
        CommandContext context = commandContext("tenant-one", "order-42");

        CompletionStage<Object> stage = assertDoesNotThrow(
            () -> registry.resolveAsync(OrderView.class, context));
        assertSame(callerThread, resolver.resolverThread);
        CompletionException asyncFailure = assertThrows(
            CompletionException.class,
            () -> stage.toCompletableFuture().join());
        assertSame(failure, asyncFailure.getCause());

        CompletionException blockingFailure = assertThrows(
            CompletionException.class,
            () -> registry.resolveBlocking(OrderView.class, context));
        assertSame(callerThread, resolver.resolverThread);
        assertSame(failure, blockingFailure.getCause());
    }

    @Test
    void equalOwnershipCollisionSelectionAndDiagnosticsAreGloballyDeterministic() {
        FirstDeclaredResolver first = new FirstDeclaredResolver(
            new LinkedHashSet<>(List.of(AlphaOrderView.class, ZuluOrderView.class)));
        SecondDeclaredResolver second = new SecondDeclaredResolver(
            new LinkedHashSet<>(List.of(ZuluOrderView.class, AlphaOrderView.class)));

        MultipleReadModelResolversForCommandException forward = assertThrows(
            MultipleReadModelResolversForCommandException.class,
            () -> new ReadModelForCommandResolverRegistry(List.of(second, first)));
        MultipleReadModelResolversForCommandException reverse = assertThrows(
            MultipleReadModelResolversForCommandException.class,
            () -> new ReadModelForCommandResolverRegistry(List.of(first, second)));
        String resolverNames = List.of(first.getClass().getName(), second.getClass().getName()).stream()
            .sorted()
            .reduce((left, right) -> left + ", " + right)
            .orElseThrow();

        assertSame(AlphaOrderView.class, forward.getReadModelType());
        assertSame(AlphaOrderView.class, reverse.getReadModelType());
        assertEquals(forward.getMessage(), reverse.getMessage());
        assertEquals(
            "Multiple command-side read-model resolvers claim '" + AlphaOrderView.class.getName()
                + "' with equal ownership: " + resolverNames + ".",
            forward.getMessage());
    }

    private static CommandContext commandContext(String tenantId, Object key) {
        TestCommand command = new TestCommand(key);
        return new CommandContext(
            UUID.randomUUID(),
            command,
            TestCommand.class,
            new ArcPrincipal("Ada", true, Set.of("operator")),
            tenantId,
            "tenant-namespace",
            null,
            NO_SERVICES);
    }

    private static final class AsyncResolver implements CanResolveReadModelForCommand {
        private final ReadModelForCommandOwnership ownership;
        private final Object value;
        private Class<?> requestedType;
        private CommandContext context;
        private Object key;

        private AsyncResolver(ReadModelForCommandOwnership ownership, Object value) {
            this.ownership = ownership;
            this.value = value;
        }

        @Override
        public Set<Class<?>> readModelTypes() {
            return Set.of(OrderView.class);
        }

        @Override
        public ReadModelForCommandOwnership ownership() {
            return ownership;
        }

        @Override
        public CompletionStage<Object> resolve(Class<?> readModelType, CommandContext commandContext, Object key) {
            requestedType = readModelType;
            context = commandContext;
            this.key = key;
            return CompletableFuture.completedFuture(value);
        }
    }

    private static final class ThrowingAsyncResolver implements CanResolveReadModelForCommand {
        private final RuntimeException failure;
        private Thread resolverThread;

        private ThrowingAsyncResolver(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public Set<Class<?>> readModelTypes() {
            return Set.of(OrderView.class);
        }

        @Override
        public ReadModelForCommandOwnership ownership() {
            return ReadModelForCommandOwnership.DECLARED;
        }

        @Override
        public CompletionStage<Object> resolve(Class<?> readModelType, CommandContext commandContext, Object key) {
            resolverThread = Thread.currentThread();
            throw failure;
        }
    }

    private static class BlockingResolver implements BlockingReadModelForCommandResolver {
        private final ReadModelForCommandOwnership ownership;
        private final Object value;
        private final RuntimeException failure;
        private final Set<Class<?>> claimedTypes;
        private CommandContext context;
        private Object key;

        private BlockingResolver(
            ReadModelForCommandOwnership ownership,
            Object value,
            RuntimeException failure) {
            this(ownership, value, failure, Set.of(OrderView.class));
        }

        private BlockingResolver(
            ReadModelForCommandOwnership ownership,
            Object value,
            RuntimeException failure,
            Set<Class<?>> claimedTypes) {
            this.ownership = ownership;
            this.value = value;
            this.failure = failure;
            this.claimedTypes = claimedTypes;
        }

        @Override
        public Set<Class<?>> readModelTypes() {
            return claimedTypes;
        }

        @Override
        public ReadModelForCommandOwnership ownership() {
            return ownership;
        }

        @Override
        public Object resolveBlocking(Class<?> readModelType, CommandContext commandContext, Object key) {
            context = commandContext;
            this.key = key;
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }

    private static final class FirstDeclaredResolver extends BlockingResolver {
        private FirstDeclaredResolver(Set<Class<?>> claimedTypes) {
            super(ReadModelForCommandOwnership.DECLARED, null, null, claimedTypes);
        }
    }

    private static final class SecondDeclaredResolver extends BlockingResolver {
        private SecondDeclaredResolver(Set<Class<?>> claimedTypes) {
            super(ReadModelForCommandOwnership.DECLARED, null, null, claimedTypes);
        }
    }

    private static final class TestCommand implements CommandKeyProvider {
        private final Object key;

        private TestCommand(Object key) {
            this.key = key;
        }

        @Override
        public Object commandKey() {
            return key;
        }
    }

    private record AlphaOrderView(String id) {
    }

    private record OrderView(String id, String source) {
    }

    private record ZuluOrderView(String id) {
    }
}
