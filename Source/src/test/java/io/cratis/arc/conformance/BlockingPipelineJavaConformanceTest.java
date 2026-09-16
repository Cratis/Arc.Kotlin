// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.authorization.AuthorizationEvaluator;
import io.cratis.arc.authorization.ConcurrentAuthorizationPolicyRegistry;
import io.cratis.arc.commands.CommandAuthorizationFilter;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandExecutionOptions;
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry;
import io.cratis.arc.commands.DefaultCommandPipeline;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.java.AsyncCommandHandler;
import io.cratis.arc.java.AsyncCommandHandlerAdapter;
import io.cratis.arc.java.AsyncQueryPerformer;
import io.cratis.arc.java.AsyncQueryPerformerAdapter;
import io.cratis.arc.java.BlockingCommandExecutionScope;
import io.cratis.arc.java.BlockingCommandExecutionScopeAdapter;
import io.cratis.arc.java.BlockingCommandPipeline;
import io.cratis.arc.java.BlockingQueryPipeline;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
import io.cratis.arc.queries.DefaultQueryPipeline;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryAuthorizationFilter;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.results.CommandResult;
import io.cratis.arc.results.QueryResult;
import io.cratis.arc.results.ValidationResultSeverity;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlockingPipelineJavaConformanceTest {
    private static final ServiceResolver SERVICES = new ServiceResolver() {
        @Override public <T> T resolve(Class<T> type) { return null; }
    };
    private static final FullyQualifiedQueryName NAME = new FullyQualifiedQueryName("Tests.query");
    private final ArcPrincipal principal = new ArcPrincipal("scheduler", true, Set.of("operator"));
    private final UUID correlation = UUID.randomUUID();
    private final CommandExecutionOptions options = new CommandExecutionOptions(
        correlation, principal, SERVICES, "tenant-a", "namespace-a", ValidationResultSeverity.Warning, true);
    private final QueryExecutionOptions queryOptions = new QueryExecutionOptions(
        correlation, principal, SERVICES, "tenant-a", "namespace-a", ValidationResultSeverity.Warning, true);

    @Test
    void ordinaryJavaCallsExecuteValidateAndQueryWithExactCapturedContext() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<CommandContext> captured = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        var handlers = handlers(context -> {
            assertSame(caller, Thread.currentThread());
            captured.set(context);
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("response");
        });
        var evaluator = new AuthorizationEvaluator(new ConcurrentAuthorizationPolicyRegistry());
        var pipeline = new DefaultCommandPipeline(handlers, List.of(new CommandAuthorizationFilter(handlers, evaluator)));
        var commands = new BlockingCommandPipeline(pipeline, options);
        assertTrue(commands.validate(new Work()).isSuccess());
        assertEquals(0, calls.get());
        CommandResult<?> result = commands.execute(new Work());
        assertTrue(result.isSuccess());
        assertEquals("response", result.getResponse());
        assertEquals(correlation, result.getCorrelationId());
        assertEquals(1, calls.get());
        CommandContext context = captured.get();
        assertSame(principal, context.getPrincipal());
        assertSame(SERVICES, context.getServiceResolver());
        assertEquals("tenant-a", context.getTenantId());
        assertEquals("namespace-a", context.getTenantNamespace());
        assertSame(ValidationResultSeverity.Warning, context.getAllowedValidationSeverity());
        assertTrue(context.getExposeExceptionDetails());
        assertEquals(correlation, context.getCorrelationId());

        var performers = performers(queryContext -> {
            assertSame(caller, Thread.currentThread());
            assertSame(principal, queryContext.getPrincipal());
            assertSame(SERVICES, queryContext.getServiceResolver());
            assertEquals("tenant-a", queryContext.getTenantId());
            assertEquals("namespace-a", queryContext.getTenantNamespace());
            assertSame(ValidationResultSeverity.Warning, queryContext.getAllowedValidationSeverity());
            assertTrue(queryContext.getExposeExceptionDetails());
            assertEquals(correlation, queryContext.getCorrelationId());
            return CompletableFuture.completedFuture("data");
        });
        var queries = new BlockingQueryPipeline(new DefaultQueryPipeline(performers,
            List.of(new QueryAuthorizationFilter(performers, evaluator))), queryOptions);
        QueryResult<?> query = queries.perform(new QueryRequest(NAME));
        assertEquals("data", query.getData());
        assertTrue(query.isSuccess());
        assertEquals(correlation, query.getCorrelationId());
        assertTrue(commands.validate(new Work(), options).isSuccess());
        assertTrue(commands.execute(new Work(), options).isSuccess());
        assertEquals("data", queries.perform(new QueryRequest(NAME), queryOptions).getData());
    }

    @Test
    void explicitAnonymousContextDoesNotBypassAuthorization() {
        AtomicInteger calls = new AtomicInteger();
        var handlers = handlers(context -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(null); });
        var performers = performers(context -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(null); });
        var evaluator = new AuthorizationEvaluator(new ConcurrentAuthorizationPolicyRegistry());
        var anonymous = new ArcPrincipal(null, false);
        var commands = new BlockingCommandPipeline(new DefaultCommandPipeline(handlers,
            List.of(new CommandAuthorizationFilter(handlers, evaluator))));
        var anonymousOptions = new CommandExecutionOptions(correlation, anonymous, SERVICES);
        assertFalse(commands.execute(new Work(), anonymousOptions).isAuthorized());
        assertFalse(commands.validate(new Work(), anonymousOptions).isAuthorized());
        var queries = new BlockingQueryPipeline(new DefaultQueryPipeline(performers,
            List.of(new QueryAuthorizationFilter(performers, evaluator))));
        assertFalse(queries.perform(new QueryRequest(NAME),
            new QueryExecutionOptions(correlation, anonymous, SERVICES)).isAuthorized());
        assertEquals(0, calls.get());
    }

    @Test
    void failedStagesRemainFailureResultsWhileCancelledStagesPropagateCancellation() {
        var failure = new IllegalStateException("handler failed");
        var commands = new BlockingCommandPipeline(new DefaultCommandPipeline(handlers(context ->
            CompletableFuture.failedFuture(failure))), options);
        CommandResult<?> commandResult = commands.execute(new Work());
        assertFalse(commandResult.isSuccess());
        assertTrue(commandResult.getExceptionMessages().contains("handler failed"));
        var queries = new BlockingQueryPipeline(new DefaultQueryPipeline(performers(context ->
            CompletableFuture.failedFuture(failure))), queryOptions);
        assertFalse(queries.perform(new QueryRequest(NAME)).isSuccess());
        var cancelled = new CompletableFuture<Object>();
        cancelled.cancel(true);
        var cancelledCommands = new BlockingCommandPipeline(new DefaultCommandPipeline(handlers(context -> cancelled)), options);
        assertThrows(CancellationException.class, () -> cancelledCommands.execute(new Work()));
        var cancelledQueries = new BlockingQueryPipeline(new DefaultQueryPipeline(performers(context -> cancelled)), queryOptions);
        assertThrows(CancellationException.class, () -> cancelledQueries.perform(new QueryRequest(NAME)));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void interruptCancelsAdapterStageAndCompletesRealCommandScopeBeforeReturning() throws Exception {
        var stage = new CompletableFuture<Object>();
        var entered = new CountDownLatch(1);
        var cleaned = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        var flag = new AtomicBoolean();
        var handlers = handlers(context -> { entered.countDown(); return stage; });
        var scope = new BlockingCommandExecutionScopeAdapter(new BlockingCommandExecutionScope() {
            @Override public void begin(CommandContext context) { }
            @Override public CommandResult<?> complete(CommandContext context, CommandResult<?> result) {
                assertFalse(result.isSuccess());
                cleaned.set(true);
                return null;
            }
        });
        var commands = new BlockingCommandPipeline(new DefaultCommandPipeline(handlers, List.of(), List.of(scope)), options);
        var thread = new Thread(() -> {
            try {
                commands.execute(new Work());
            } catch (CancellationException exception) {
                failure.set(exception);
                flag.set(Thread.currentThread().isInterrupted());
                assertTrue(cleaned.get());
            }
        });
        thread.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        } finally {
            thread.interrupt();
            thread.join(5000);
        }
        assertFalse(thread.isAlive());
        assertTrue(stage.isCancelled());
        assertTrue(cleaned.get());
        assertTrue(failure.get() instanceof CancellationException);
        assertTrue(failure.get().getCause() instanceof InterruptedException);
        assertTrue(flag.get());
    }

    private static ConcurrentCommandHandlerRegistry handlers(java.util.function.Function<CommandContext, CompletionStage<?>> invoke) {
        var registry = new ConcurrentCommandHandlerRegistry();
        registry.register(new AsyncCommandHandlerAdapter(new AsyncCommandHandler() {
            @Override public Class<?> getCommandType() { return Work.class; }
            @Override public CommandDescriptor getMetadata() { return new CommandDescriptor("Work", Work.class.getName()); }
            @Override public CompletionStage<?> invoke(CommandContext context) { return invoke.apply(context); }
        }));
        return registry;
    }

    private static ConcurrentQueryPerformerRegistry performers(java.util.function.Function<QueryContext, CompletionStage<?>> perform) {
        var registry = new ConcurrentQueryPerformerRegistry();
        registry.register(new AsyncQueryPerformerAdapter(new AsyncQueryPerformer() {
            @Override public QueryDescriptor getDescriptor() { return new QueryDescriptor("query", "Tests", String.class.getName()); }
            @Override public FullyQualifiedQueryName getFullyQualifiedName() { return NAME; }
            @Override public CompletionStage<?> perform(QueryContext context) { return perform.apply(context); }
        }));
        return registry;
    }

    private record Work() { }
}
