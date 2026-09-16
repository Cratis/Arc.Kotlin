// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.artifacts.CommandEventStreamIdProvider;
import io.cratis.arc.artifacts.CommandEventSubjectProvider;
import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandExecutionOptions;
import io.cratis.arc.commands.CommandKeyProvider;
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry;
import io.cratis.arc.commands.DefaultCommandPipeline;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.java.AsyncCommandHandler;
import io.cratis.arc.java.AsyncCommandHandlerAdapter;
import io.cratis.arc.java.BlockingCommandExecutionScope;
import io.cratis.arc.java.BlockingCommandExecutionScopeAdapter;
import io.cratis.arc.java.BlockingCommandFilterAdapter;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.results.CommandResult;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CommandContextFailureJavaConformanceTest {
    private static final UUID CORRELATION = UUID.fromString("315faf8c-07fc-4d8e-a67b-e53a15b4a59f");
    private static final ServiceResolver NO_SERVICES = new ServiceResolver() {
        @Override public <T> T resolve(Class<T> type) { return null; }
    };

    @ParameterizedTest
    @EnumSource(Stage.class)
    void throwingProvidersCompleteWithCorrelatedResultsForExecuteAndValidate(Stage stage) throws Exception {
        Fixture fixture = new Fixture();
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(Executors.newSingleThreadExecutor())) {
            var commands = scope.commands(fixture.pipeline);
            for (boolean validateOnly : List.of(false, true)) {
                fixture.calls.clear();
                TestCommand command = fixture.command(stage, new IllegalStateException("provider failed"));
                CompletionStage<CommandResult<?>> execution = validateOnly
                    ? commands.validate(command, options()) : commands.execute(command, options());
                CommandResult<?> result = execution.toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertFailure(result);
                assertEquals(prefix(stage), fixture.calls);
                assertTrue(fixture.completions.isEmpty());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void providerCancellationCancelsTheCompletionStageForExecuteAndValidate(Stage stage) {
        Fixture fixture = new Fixture();
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(Executors.newSingleThreadExecutor())) {
            var commands = scope.commands(fixture.pipeline);
            for (boolean validateOnly : List.of(false, true)) {
                fixture.calls.clear();
                TestCommand command = fixture.command(stage, new CancellationException("provider cancelled"));
                CompletionStage<CommandResult<?>> execution = validateOnly
                    ? commands.validate(command, options()) : commands.execute(command, options());
                CompletableFuture<CommandResult<?>> future = execution.toCompletableFuture();
                assertThrows(CancellationException.class, () -> future.get(5, TimeUnit.SECONDS));
                assertTrue(future.isCancelled());
                assertEquals(prefix(stage), fixture.calls);
                assertTrue(fixture.completions.isEmpty());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void ignoredAsyncChildProviderFailureOrCancellationPreventsRootSuccess(Stage stage) throws Exception {
        for (boolean cancel : List.of(false, true)) {
            Fixture fixture = new Fixture();
            try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(Executors.newSingleThreadExecutor())) {
                var commands = scope.commands(fixture.pipeline);
                fixture.operation = context -> commands.execute(
                    fixture.command(stage, cancel
                        ? new CancellationException("provider cancelled") : new IllegalStateException("provider failed")),
                    CommandExecutionOptions.nested(context)
                ).handle((childResult, failure) -> {
                    if (cancel) {
                        assertTrue(failure instanceof CancellationException);
                        assertNull(childResult);
                    } else {
                        assertNull(failure);
                        assertFailure(childResult);
                    }
                    return "must be cleared";
                });

                CommandResult<?> result = commands.execute(fixture.command(null, null), options())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);

                assertFalse(result.isSuccess());
                assertEquals(CORRELATION, result.getCorrelationId());
                assertEquals(List.of("A nested command execution failed; the root execution is rollback-only."),
                    result.getExceptionMessages());
                assertNull(result.getResponse());
                assertEquals(1, fixture.completions.size());
                assertFalse(fixture.completions.get(0).isSuccess());
            }
        }
    }

    private static void assertFailure(CommandResult<?> result) {
        assertFalse(result.isSuccess());
        assertTrue(result.getHasExceptions());
        assertEquals(CORRELATION, result.getCorrelationId());
        assertEquals(List.of("provider failed"), result.getExceptionMessages());
        assertTrue(result.getExceptionStackTrace().contains("IllegalStateException: provider failed"));
        assertNull(result.getResponse());
    }

    private static CommandExecutionOptions options() {
        return new CommandExecutionOptions(CORRELATION, ArcPrincipal.anonymous(), NO_SERVICES);
    }

    private static List<String> prefix(Stage stage) {
        return List.of(Stage.values()).subList(0, stage.ordinal() + 1).stream().map(Enum::name).toList();
    }

    enum Stage { VALUES_FIRST, VALUES_SECOND, KEY, STREAM, SUBJECT }

    private record TestCommand(Stage failureStage, RuntimeException failure, List<String> calls)
        implements CommandKeyProvider, CommandEventStreamIdProvider, CommandEventSubjectProvider {
        void visit(Stage stage) {
            calls.add(stage.name());
            if (stage == failureStage) throw failure;
        }
        @Override public Object commandKey() { visit(Stage.KEY); return "key"; }
        @Override public String eventStreamId() { visit(Stage.STREAM); return "stream"; }
        @Override public String eventSubject() { visit(Stage.SUBJECT); return "subject"; }
    }

    private static final class Fixture {
        private final List<String> calls = new ArrayList<>();
        private final List<CommandResult<?>> completions = new ArrayList<>();
        private Function<CommandContext, CompletionStage<?>> operation = context -> CompletableFuture.completedFuture("response");
        private final DefaultCommandPipeline pipeline;

        Fixture() {
            var registry = new ConcurrentCommandHandlerRegistry();
            registry.register(new AsyncCommandHandlerAdapter(new AsyncCommandHandler() {
                @Override public Class<?> getCommandType() { return TestCommand.class; }
                @Override public CommandDescriptor getMetadata() {
                    return new CommandDescriptor("TestCommand", TestCommand.class.getName());
                }
                @Override public CompletionStage<?> invoke(CommandContext context) {
                    calls.add("invoke");
                    return operation.apply(context);
                }
            }));
            pipeline = new DefaultCommandPipeline(registry,
                List.of(new BlockingCommandFilterAdapter(context -> {
                    calls.add("filter");
                    return CommandResult.success(context.getCorrelationId());
                })),
                List.of(new BlockingCommandExecutionScopeAdapter(new BlockingCommandExecutionScope() {
                    @Override public void begin(CommandContext context) { calls.add("begin"); }
                    @Override public CommandResult<?> complete(CommandContext context, CommandResult<?> result) {
                        calls.add("complete");
                        completions.add(result);
                        return null;
                    }
                })), List.of(), List.of(
                    command -> { ((TestCommand) command).visit(Stage.VALUES_FIRST); return Map.of(); },
                    command -> { ((TestCommand) command).visit(Stage.VALUES_SECOND); return Map.of(); }
                ));
        }

        TestCommand command(Stage stage, RuntimeException failure) { return new TestCommand(stage, failure, calls); }
    }
}
