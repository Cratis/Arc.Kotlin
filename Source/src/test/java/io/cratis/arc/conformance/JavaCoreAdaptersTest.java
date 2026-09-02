// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authentication.AsyncAuthenticationHandlerAdapter;
import io.cratis.arc.authentication.AuthenticationResult;
import io.cratis.arc.authentication.DefaultAuthentication;
import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.authorization.AuthorizationEvaluator;
import io.cratis.arc.authorization.AuthorizationResult;
import io.cratis.arc.authorization.ConcurrentAuthorizationPolicyRegistry;
import io.cratis.arc.commands.CommandAuthorizationFilter;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandExecutionOptions;
import io.cratis.arc.commands.CommandPreparation;
import io.cratis.arc.commands.CommandResponseValues;
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry;
import io.cratis.arc.commands.DefaultCommandPipeline;
import io.cratis.arc.commands.DefaultCommandValidationFilter;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.java.AsyncAuthorizationCommandFilter;
import io.cratis.arc.java.AsyncAuthorizationCommandFilterAdapter;
import io.cratis.arc.java.AsyncAuthorizationPolicy;
import io.cratis.arc.java.AsyncAuthorizationPolicyAdapter;
import io.cratis.arc.java.AsyncAuthorizationQueryFilter;
import io.cratis.arc.java.AsyncAuthorizationQueryFilterAdapter;
import io.cratis.arc.java.AsyncCommandExecutionScope;
import io.cratis.arc.java.AsyncCommandExecutionScopeAdapter;
import io.cratis.arc.java.AsyncCommandFilter;
import io.cratis.arc.java.AsyncCommandFilterAdapter;
import io.cratis.arc.java.AsyncCommandHandler;
import io.cratis.arc.java.AsyncCommandHandlerAdapter;
import io.cratis.arc.java.AsyncCommandResponseValueHandler;
import io.cratis.arc.java.AsyncCommandResponseValueHandlerAdapter;
import io.cratis.arc.java.AsyncCommandValidator;
import io.cratis.arc.java.AsyncCommandValidatorAdapter;
import io.cratis.arc.java.AsyncQueryFilter;
import io.cratis.arc.java.AsyncQueryFilterAdapter;
import io.cratis.arc.java.AsyncQueryPerformer;
import io.cratis.arc.java.AsyncQueryPerformerAdapter;
import io.cratis.arc.java.AsyncQueryValidator;
import io.cratis.arc.java.AsyncQueryValidatorAdapter;
import io.cratis.arc.java.BlockingAuthorizationCommandFilter;
import io.cratis.arc.java.BlockingAuthorizationCommandFilterAdapter;
import io.cratis.arc.java.BlockingAuthorizationPolicy;
import io.cratis.arc.java.BlockingAuthorizationPolicyAdapter;
import io.cratis.arc.java.BlockingAuthorizationQueryFilter;
import io.cratis.arc.java.BlockingAuthorizationQueryFilterAdapter;
import io.cratis.arc.java.BlockingCommandExecutionScope;
import io.cratis.arc.java.BlockingCommandExecutionScopeAdapter;
import io.cratis.arc.java.BlockingCommandFilter;
import io.cratis.arc.java.BlockingCommandFilterAdapter;
import io.cratis.arc.java.BlockingCommandHandler;
import io.cratis.arc.java.BlockingCommandHandlerAdapter;
import io.cratis.arc.java.BlockingCommandResponseValueHandler;
import io.cratis.arc.java.BlockingCommandResponseValueHandlerAdapter;
import io.cratis.arc.java.BlockingCommandValidator;
import io.cratis.arc.java.BlockingCommandValidatorAdapter;
import io.cratis.arc.java.BlockingQueryFilter;
import io.cratis.arc.java.BlockingQueryFilterAdapter;
import io.cratis.arc.java.BlockingQueryPerformer;
import io.cratis.arc.java.BlockingQueryPerformerAdapter;
import io.cratis.arc.java.BlockingQueryValidator;
import io.cratis.arc.java.BlockingQueryValidatorAdapter;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.metadata.AuthorizationMetadata;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.metadata.RouteOptions;
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
import io.cratis.arc.queries.DefaultQueryPipeline;
import io.cratis.arc.queries.DefaultQueryValidationFilter;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryAuthorizationFilter;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.results.CommandResult;
import io.cratis.arc.results.QueryResult;
import io.cratis.arc.results.ValidationResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JavaCoreAdaptersTest {
    private static final ServiceResolver NO_SERVICES = new ServiceResolver() {
        @Override
        public <T> T resolve(Class<T> type) {
            return null;
        }
    };

    @Test
    void blockingAndAsyncCommandAdaptersRunThroughThePrimaryPipeline() {
        AtomicInteger calls = new AtomicInteger();
        ConcurrentCommandHandlerRegistry handlers = new ConcurrentCommandHandlerRegistry();
        handlers.register(new BlockingCommandHandlerAdapter(blockingCommandHandler(calls)));
        handlers.register(new AsyncCommandHandlerAdapter(asyncCommandHandler(calls)));

        ConcurrentAuthorizationPolicyRegistry policies = new ConcurrentAuthorizationPolicyRegistry();
        BlockingAuthorizationPolicy blockingPolicy = principal -> {
            calls.incrementAndGet();
            return AuthorizationResult.success();
        };
        AsyncAuthorizationPolicy asyncPolicy = principal -> completed(AuthorizationResult.success());
        policies.register("blocking", new BlockingAuthorizationPolicyAdapter(blockingPolicy));
        policies.register("async", new AsyncAuthorizationPolicyAdapter(asyncPolicy));

        BlockingCommandValidator<BlockingCommand> blockingValidator = new BlockingCommandValidator<>() {
            @Override public Class<BlockingCommand> getCommandType() { return BlockingCommand.class; }
            @Override public List<ValidationResult> validate(BlockingCommand command, CommandContext context) {
                calls.incrementAndGet();
                return List.of();
            }
        };
        AsyncCommandValidator<AsyncCommand> asyncValidator = new AsyncCommandValidator<>() {
            @Override public Class<AsyncCommand> getCommandType() { return AsyncCommand.class; }
            @Override public java.util.concurrent.CompletionStage<List<ValidationResult>> validate(
                AsyncCommand command, CommandContext context) {
                calls.incrementAndGet();
                return completed(List.of());
            }
        };

        BlockingAuthorizationCommandFilter blockingAuthorization = context -> {
            calls.incrementAndGet();
            return CommandResult.success(context.getCorrelationId());
        };
        AsyncAuthorizationCommandFilter asyncAuthorization = context -> {
            calls.incrementAndGet();
            return completedResult(CommandResult.success(context.getCorrelationId()));
        };
        BlockingCommandFilter blockingFilter = context -> {
            calls.incrementAndGet();
            return CommandResult.success(context.getCorrelationId());
        };
        AsyncCommandFilter asyncFilter = context -> {
            calls.incrementAndGet();
            return completedResult(CommandResult.success(context.getCorrelationId()));
        };

        BlockingCommandExecutionScope blockingScope = new BlockingCommandExecutionScope() {
            @Override public void begin(CommandContext context) { calls.incrementAndGet(); }
            @Override public CommandResult<?> complete(CommandContext context, CommandResult<?> result) {
                calls.incrementAndGet();
                return null;
            }
        };
        AsyncCommandExecutionScope asyncScope = new AsyncCommandExecutionScope() {
            @Override public void begin(CommandContext context) { calls.incrementAndGet(); }
            @Override public java.util.concurrent.CompletionStage<CommandResult<?>> complete(
                CommandContext context, CommandResult<?> result) {
                calls.incrementAndGet();
                return completed(null);
            }
        };

        BlockingCommandResponseValueHandler blockingValueHandler = new BlockingCommandResponseValueHandler() {
            @Override public boolean canHandle(CommandContext context, Object value) { return value instanceof BlockingValue; }
            @Override public CommandResult<?> handle(CommandContext context, Object value) {
                calls.incrementAndGet();
                return CommandResult.success(context.getCorrelationId());
            }
        };
        AsyncCommandResponseValueHandler asyncValueHandler = new AsyncCommandResponseValueHandler() {
            @Override public boolean canHandle(CommandContext context, Object value) { return value instanceof AsyncValue; }
            @Override public java.util.concurrent.CompletionStage<CommandResult<?>> handle(CommandContext context, Object value) {
                calls.incrementAndGet();
                return completedResult(CommandResult.success(context.getCorrelationId()));
            }
        };

        DefaultCommandValidationFilter validation = new DefaultCommandValidationFilter(List.of(
            new BlockingCommandValidatorAdapter<>(blockingValidator),
            new AsyncCommandValidatorAdapter<>(asyncValidator)));
        DefaultCommandPipeline pipeline = new DefaultCommandPipeline(
            handlers,
            List.of(
                new CommandAuthorizationFilter(handlers, new AuthorizationEvaluator(policies)),
                new BlockingAuthorizationCommandFilterAdapter(blockingAuthorization),
                new AsyncAuthorizationCommandFilterAdapter(asyncAuthorization),
                new BlockingCommandFilterAdapter(blockingFilter),
                new AsyncCommandFilterAdapter(asyncFilter),
                validation),
            List.of(
                new BlockingCommandExecutionScopeAdapter(blockingScope),
                new AsyncCommandExecutionScopeAdapter(asyncScope)),
            List.of(
                new BlockingCommandResponseValueHandlerAdapter(blockingValueHandler),
                new AsyncCommandResponseValueHandlerAdapter(asyncValueHandler)));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(executor)) {
                CommandResult<?> first = scope.commands(pipeline)
                    .execute(new BlockingCommand("one"), commandOptions()).toCompletableFuture().join();
                CommandResult<?> second = scope.commands(pipeline)
                    .execute(new AsyncCommand("two"), commandOptions()).toCompletableFuture().join();
                assertTrue(first.isSuccess());
                assertTrue(second.isSuccess());
                assertEquals(25, calls.get());
            }
            assertFalse(executor.isShutdown());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void blockingAndAsyncQueryAdaptersRunThroughThePrimaryPipeline() {
        AtomicInteger calls = new AtomicInteger();
        FullyQualifiedQueryName blockingName = new FullyQualifiedQueryName("Tests.blocking");
        FullyQualifiedQueryName asyncName = new FullyQualifiedQueryName("Tests.async");
        ConcurrentQueryPerformerRegistry performers = new ConcurrentQueryPerformerRegistry();
        performers.register(new BlockingQueryPerformerAdapter(new BlockingQueryPerformer() {
            @Override public QueryDescriptor getDescriptor() { return queryDescriptor("blocking", blockingName, "blocking"); }
            @Override public FullyQualifiedQueryName getFullyQualifiedName() { return blockingName; }
            @Override public Object perform(QueryContext context) { calls.incrementAndGet(); return "blocking"; }
        }));
        performers.register(new AsyncQueryPerformerAdapter(new AsyncQueryPerformer() {
            @Override public QueryDescriptor getDescriptor() { return queryDescriptor("async", asyncName, "async"); }
            @Override public FullyQualifiedQueryName getFullyQualifiedName() { return asyncName; }
            @Override public java.util.concurrent.CompletionStage<?> perform(QueryContext context) {
                calls.incrementAndGet();
                return completed("async");
            }
        }));

        ConcurrentAuthorizationPolicyRegistry policies = new ConcurrentAuthorizationPolicyRegistry();
        policies.register("blocking", new BlockingAuthorizationPolicyAdapter(principal -> {
            calls.incrementAndGet();
            return AuthorizationResult.success();
        }));
        policies.register("async", new AsyncAuthorizationPolicyAdapter(principal -> {
            calls.incrementAndGet();
            return completed(AuthorizationResult.success());
        }));

        BlockingQueryValidator blockingValidator = new BlockingQueryValidator() {
            @Override public FullyQualifiedQueryName getQueryName() { return blockingName; }
            @Override public List<ValidationResult> validate(QueryRequest request, QueryContext context) {
                calls.incrementAndGet();
                return List.of();
            }
        };
        AsyncQueryValidator asyncValidator = new AsyncQueryValidator() {
            @Override public FullyQualifiedQueryName getQueryName() { return asyncName; }
            @Override public java.util.concurrent.CompletionStage<List<ValidationResult>> validate(
                QueryRequest request, QueryContext context) {
                calls.incrementAndGet();
                return completed(List.of());
            }
        };
        BlockingAuthorizationQueryFilter blockingAuthorization = context -> {
            calls.incrementAndGet();
            return QueryResult.success(context.getCorrelationId());
        };
        AsyncAuthorizationQueryFilter asyncAuthorization = context -> {
            calls.incrementAndGet();
            return completedQueryResult(QueryResult.success(context.getCorrelationId()));
        };
        BlockingQueryFilter blockingFilter = context -> {
            calls.incrementAndGet();
            return QueryResult.success(context.getCorrelationId());
        };
        AsyncQueryFilter asyncFilter = context -> {
            calls.incrementAndGet();
            return completedQueryResult(QueryResult.success(context.getCorrelationId()));
        };

        DefaultQueryPipeline pipeline = new DefaultQueryPipeline(performers, List.of(
            new QueryAuthorizationFilter(performers, new AuthorizationEvaluator(policies)),
            new BlockingAuthorizationQueryFilterAdapter(blockingAuthorization),
            new AsyncAuthorizationQueryFilterAdapter(asyncAuthorization),
            new BlockingQueryFilterAdapter(blockingFilter),
            new AsyncQueryFilterAdapter(asyncFilter),
            new DefaultQueryValidationFilter(List.of(
                new BlockingQueryValidatorAdapter(blockingValidator),
                new AsyncQueryValidatorAdapter(asyncValidator)))));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(executor)) {
            QueryResult<?> first = scope.queries(pipeline)
                .perform(new QueryRequest(blockingName), queryOptions()).toCompletableFuture().join();
            QueryResult<?> second = scope.queries(pipeline)
                .perform(new QueryRequest(asyncName), queryOptions()).toCompletableFuture().join();
            assertEquals("blocking", first.getData());
            assertEquals("async", second.getData());
            assertEquals(14, calls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void closingAnOwnedScopeCancelsInFlightStagesAndShutsDownItsExecutor() throws Exception {
        CountDownLatch invoked = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CompletableFuture<Object> handlerResult = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                cancelled.countDown();
                return super.cancel(mayInterruptIfRunning);
            }
        };
        ConcurrentCommandHandlerRegistry handlers = new ConcurrentCommandHandlerRegistry();
        handlers.register(new AsyncCommandHandlerAdapter(new AsyncCommandHandler() {
            @Override public Class<?> getCommandType() { return AsyncCommand.class; }
            @Override public CommandDescriptor getMetadata() { return commandDescriptor(AsyncCommand.class, "async"); }
            @Override public java.util.concurrent.CompletionStage<?> invoke(CommandContext context) {
                invoked.countDown();
                return handlerResult;
            }
        }));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<CommandResult<?>> execution;

        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(executor)) {
            execution = scope.commands(new DefaultCommandPipeline(handlers))
                .execute(new AsyncCommand("one"), commandOptions()).toCompletableFuture();
            assertTrue(invoked.await(2, TimeUnit.SECONDS));
        }

        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        assertThrows(CancellationException.class, () -> execution.get(2, TimeUnit.SECONDS));
        assertTrue(handlerResult.isCancelled());
        assertTrue(execution.isCancelled());
        assertTrue(executor.isShutdown());
    }

    @Test
    void ownedFactoriesCloseTheirExecutorServices() {
        ConcurrentCommandHandlerRegistry commandHandlers = new ConcurrentCommandHandlerRegistry();
        commandHandlers.register(new BlockingCommandHandlerAdapter(blockingCommandHandler(new AtomicInteger())));
        ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(commandExecutor)) {
            assertTrue(scope.commands(new DefaultCommandPipeline(commandHandlers))
                .execute(new BlockingCommand("one"), commandOptions()).toCompletableFuture().join().isSuccess());
        }
        assertTrue(commandExecutor.isShutdown());

        FullyQualifiedQueryName queryName = new FullyQualifiedQueryName("Tests.blocking");
        ConcurrentQueryPerformerRegistry queryPerformers = new ConcurrentQueryPerformerRegistry();
        queryPerformers.register(new BlockingQueryPerformerAdapter(new BlockingQueryPerformer() {
            @Override public QueryDescriptor getDescriptor() { return new QueryDescriptor("blocking", "Tests", String.class.getName()); }
            @Override public FullyQualifiedQueryName getFullyQualifiedName() { return queryName; }
            @Override public Object perform(QueryContext context) { return "value"; }
        }));
        ExecutorService queryExecutor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(queryExecutor)) {
            assertEquals("value", scope.queries(new DefaultQueryPipeline(queryPerformers))
                .perform(new QueryRequest(queryName), queryOptions()).toCompletableFuture().join().getData());
        }
        assertTrue(queryExecutor.isShutdown());

        DefaultAuthentication authentication = new DefaultAuthentication(List.of(
            new AsyncAuthenticationHandlerAdapter(context -> completed(AuthenticationResult.ANONYMOUS))));
        ExecutorService authenticationExecutor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(authenticationExecutor)) {
            assertEquals(AuthenticationResult.ANONYMOUS, scope.authentication(authentication).handleAuthentication(
                new io.cratis.arc.authentication.AuthenticationRequestContext()).toCompletableFuture().join());
        }
        assertTrue(authenticationExecutor.isShutdown());
    }

    private static BlockingCommandHandler blockingCommandHandler(AtomicInteger calls) {
        return new BlockingCommandHandler() {
            @Override public Class<?> getCommandType() { return BlockingCommand.class; }
            @Override public CommandDescriptor getMetadata() { return commandDescriptor(BlockingCommand.class, "blocking"); }
            @Override public CommandPreparation prepare(CommandContext context) {
                calls.incrementAndGet();
                return CommandPreparation.empty(context.getCorrelationId());
            }
            @Override public Object invoke(CommandContext context) {
                calls.incrementAndGet();
                return CommandResponseValues.builder().add(new BlockingValue()).build();
            }
        };
    }

    private static AsyncCommandHandler asyncCommandHandler(AtomicInteger calls) {
        return new AsyncCommandHandler() {
            @Override public Class<?> getCommandType() { return AsyncCommand.class; }
            @Override public CommandDescriptor getMetadata() { return commandDescriptor(AsyncCommand.class, "async"); }
            @Override public java.util.concurrent.CompletionStage<CommandPreparation> prepare(CommandContext context) {
                calls.incrementAndGet();
                return completed(CommandPreparation.empty(context.getCorrelationId()));
            }
            @Override public java.util.concurrent.CompletionStage<?> invoke(CommandContext context) {
                calls.incrementAndGet();
                return completed(CommandResponseValues.builder().add(new AsyncValue()).build());
            }
        };
    }

    private static CommandDescriptor commandDescriptor(Class<?> type, String policy) {
        return new CommandDescriptor(
            type.getSimpleName(), type.getName(), List.of(), new RouteOptions(), List.of("tests"),
            new AuthorizationMetadata(false, policy, List.of(), List.of()));
    }

    private static QueryDescriptor queryDescriptor(
        String name, FullyQualifiedQueryName fullyQualifiedName, String policy) {
        return new QueryDescriptor(
            name, "Tests", String.class.getName(), List.of(), new RouteOptions(), fullyQualifiedName.value(),
            List.of("tests"), new AuthorizationMetadata(false, policy, List.of(), List.of()));
    }

    private static CommandExecutionOptions commandOptions() {
        return new CommandExecutionOptions(UUID.randomUUID(), new ArcPrincipal("Ada", true, Set.of("operator")), NO_SERVICES);
    }

    private static QueryExecutionOptions queryOptions() {
        return new QueryExecutionOptions(UUID.randomUUID(), new ArcPrincipal("Ada", true, Set.of("operator")), NO_SERVICES);
    }

    private static <T> java.util.concurrent.CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static java.util.concurrent.CompletionStage<CommandResult<?>> completedResult(CommandResult<?> value) {
        return CompletableFuture.completedFuture(value);
    }

    private static java.util.concurrent.CompletionStage<QueryResult<?>> completedQueryResult(QueryResult<?> value) {
        return CompletableFuture.completedFuture(value);
    }

    private record BlockingCommand(String value) { }
    private record AsyncCommand(String value) { }
    private record BlockingValue() { }
    private record AsyncValue() { }
}
