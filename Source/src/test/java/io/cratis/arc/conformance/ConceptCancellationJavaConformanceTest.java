// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandExecutionOptions;
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry;
import io.cratis.arc.commands.DefaultCommandPipeline;
import io.cratis.arc.commands.DefaultCommandValidationFilter;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.concepts.ConceptAs;
import io.cratis.arc.java.AsyncCommandHandler;
import io.cratis.arc.java.AsyncCommandHandlerAdapter;
import io.cratis.arc.java.BlockingCommandExecutionScope;
import io.cratis.arc.java.BlockingCommandExecutionScopeAdapter;
import io.cratis.arc.java.BlockingQueryPerformer;
import io.cratis.arc.java.BlockingQueryPerformerAdapter;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.metadata.RouteOptions;
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
import io.cratis.arc.queries.DefaultObservableQueryPipeline;
import io.cratis.arc.queries.DefaultQueryPipeline;
import io.cratis.arc.queries.DefaultQueryValidationFilter;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.queries.QueryTransportType;
import io.cratis.arc.results.CommandResult;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.validation.ConceptValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConceptCancellationJavaConformanceTest {
    enum Boundary { EXECUTE, VALIDATE, QUERY, OBSERVABLE }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void leafValidatorCancellationCancelsJavaFutureBeforeInvocation(Boundary boundary) {
        Fixture fixture = new Fixture(new CancellationException("concept cancelled"));
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(Executors.newSingleThreadExecutor())) {
            assertCancelled(fixture.run(scope, boundary, new Input(new Code("value"))));
            fixture.assertNotInvoked(boundary);
        }
    }

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void recordAccessorCancellationCancelsJavaFutureBeforeInvocation(Boundary boundary) {
        Fixture fixture = new Fixture(null);
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(Executors.newSingleThreadExecutor())) {
            assertCancelled(fixture.run(scope, boundary, new ThrowingAccessor(new CancellationException("accessor cancelled"))));
            fixture.assertNotInvoked(boundary);
        }
    }

    @Test
    void ordinaryRecordAccessorFailureRemainsAnExceptionResultNotValidationFeedback() throws Exception {
        Fixture fixture = new Fixture(null);
        try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(Runnable::run)) {
            var result = scope.commands(fixture.commands).execute(new ThrowingAccessor(new IllegalStateException("unreadable")), fixture.commandOptions)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertFalse(result.isSuccess());
            assertTrue(result.getHasExceptions());
            assertTrue(result.getExceptionStackTrace().contains("InvocationTargetException"));
            assertTrue(result.getExceptionStackTrace().contains("IllegalStateException: unreadable"));
            assertTrue(result.getValidationResults().isEmpty());
            assertEquals(0, fixture.invocations);
        }
    }

    @Test
    void ignoredNestedCancelledFutureStillMakesRootRollbackOnly() throws Exception {
        Fixture fixture = new Fixture(new CancellationException("child cancelled"));
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(Executors.newSingleThreadExecutor())) {
            var commands = scope.commands(fixture.commands);
            fixture.rootOperation = context -> commands.execute(new Input(new Code("child")), CommandExecutionOptions.nested(context))
                .handle((result, failure) -> {
                    assertNull(result);
                    assertTrue(failure instanceof CancellationException);
                    return "must be cleared";
                });
            var result = commands.execute(new Root(), fixture.commandOptions).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertFalse(result.isSuccess());
            assertNull(result.getResponse());
            assertEquals(List.of("A nested command execution failed; the root execution is rollback-only."), result.getExceptionMessages());
            assertEquals(1, fixture.invocations);
            assertEquals(List.of(false, true), fixture.completionRoots);
            fixture.completions.forEach(completion -> {
                assertFalse(completion.isSuccess());
                assertTrue(completion.getValidationResults().isEmpty());
            });
        }
    }

    private static void assertCancelled(CompletionStage<?> stage) {
        var future = stage.toCompletableFuture();
        assertThrows(CancellationException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertTrue(future.isCancelled());
    }

    public record Code(String value) implements ConceptAs<String> { }
    public record Input(Code code) { }
    public record Root() { }
    public record ThrowingAccessor(RuntimeException failure) {
        @Override public RuntimeException failure() { throw failure; }
    }

    private static final class Fixture {
        int invocations;
        final List<Boolean> completionRoots = new ArrayList<>();
        final List<CommandResult<?>> completions = new ArrayList<>();
        Function<CommandContext, CompletionStage<?>> rootOperation = context -> CompletableFuture.completedFuture("root");
        final ServiceResolver services = new ServiceResolver() {
            @Override public <T> T resolve(Class<T> type) { return null; }
        };
        final CommandExecutionOptions commandOptions = new CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), services);
        final QueryExecutionOptions queryOptions = new QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), services);
        final DefaultCommandPipeline commands;
        final ConcurrentQueryPerformerRegistry performers = new ConcurrentQueryPerformerRegistry();
        final DefaultQueryValidationFilter queryFilter;

        Fixture(RuntimeException failure) {
            ConceptValidator<Code> validator = new ConceptValidator<>() {
                @Override public Class<Code> getConceptType() { return Code.class; }
                @Override public List<ValidationResult> validate(Code concept) {
                    if (failure != null) throw failure;
                    return List.of();
                }
            };
            var handlers = new ConcurrentCommandHandlerRegistry();
            for (Class<?> type : List.of(Input.class, ThrowingAccessor.class, Root.class)) {
                handlers.register(new AsyncCommandHandlerAdapter(new AsyncCommandHandler() {
                    @Override public Class<?> getCommandType() { return type; }
                    @Override public CommandDescriptor getMetadata() { return new CommandDescriptor(type.getSimpleName(), type.getName()); }
                    @Override public CompletionStage<?> invoke(CommandContext context) {
                        invocations++;
                        return context.getCommand() instanceof Root ? rootOperation.apply(context) : CompletableFuture.completedFuture("response");
                    }
                }));
            }
            commands = new DefaultCommandPipeline(handlers,
                List.of(new DefaultCommandValidationFilter(List.of(), List.of(validator))),
                List.of(new BlockingCommandExecutionScopeAdapter(new BlockingCommandExecutionScope() {
                    @Override public void begin(CommandContext context) { }
                    @Override public CommandResult<?> complete(CommandContext context, CommandResult<?> result) {
                        completionRoots.add(context.getCommand() instanceof Root);
                        completions.add(result);
                        return null;
                    }
                })));
            queryFilter = new DefaultQueryValidationFilter(List.of(), List.of(validator));
            for (var transport : QueryTransportType.values()) {
                performers.register(new BlockingQueryPerformerAdapter(new BlockingQueryPerformer() {
                    @Override public FullyQualifiedQueryName getFullyQualifiedName() { return new FullyQualifiedQueryName("test." + transport); }
                    @Override public QueryDescriptor getDescriptor() {
                        return new QueryDescriptor(transport.name(), "test", "java.lang.String", List.of(), new RouteOptions(null, transport));
                    }
                    @Override public Object perform(QueryContext context) {
                        invocations++;
                        return "data";
                    }
                }));
            }
        }

        CompletionStage<?> run(JavaAsyncScope scope, Boundary boundary, Object input) {
            return switch (boundary) {
                case EXECUTE -> scope.commands(commands).execute(input, commandOptions);
                case VALIDATE -> scope.commands(commands).validate(input, commandOptions);
                case QUERY -> scope.queries(new DefaultQueryPipeline(performers, List.of(queryFilter)))
                    .perform(new QueryRequest(new FullyQualifiedQueryName("test.REQUEST_RESPONSE"), Map.of("input", input)), queryOptions);
                case OBSERVABLE -> scope.observableQueries(new DefaultObservableQueryPipeline(performers, List.of(queryFilter)))
                    .open(new QueryRequest(new FullyQualifiedQueryName("test.OBSERVABLE"), Map.of("input", input)), queryOptions);
            };
        }

        void assertNotInvoked(Boundary boundary) {
            assertEquals(0, invocations);
            assertEquals(boundary == Boundary.EXECUTE ? 1 : 0, completions.size());
            completions.forEach(result -> {
                assertFalse(result.isSuccess());
                assertTrue(result.getHasExceptions());
                assertTrue(result.getValidationResults().isEmpty());
            });
        }
    }
}
