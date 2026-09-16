// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandExecutionOptions;
import io.cratis.arc.commands.CommandPreparation;
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry;
import io.cratis.arc.commands.DefaultCommandPipeline;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.java.AsyncCommandExecutionScope;
import io.cratis.arc.java.AsyncCommandExecutionScopeAdapter;
import io.cratis.arc.java.AsyncCommandHandler;
import io.cratis.arc.java.AsyncCommandHandlerAdapter;
import io.cratis.arc.java.AsyncCommandResponseValueHandler;
import io.cratis.arc.java.AsyncCommandResponseValueHandlerAdapter;
import io.cratis.arc.java.BlockingCommandHandler;
import io.cratis.arc.java.BlockingCommandHandlerAdapter;
import io.cratis.arc.java.BlockingCommandFilterAdapter;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.results.CommandResult;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.validation.ValidationFailure;
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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CommandValidationFailureJavaConformanceTest {
    private static final UUID ID = UUID.randomUUID();
    private static final ValidationResult FEEDBACK = ValidationResult.error("safe", List.of("value"), Map.of("visible", 7), "reason", "detail");
    private static final ServiceResolver NO_SERVICES = new ServiceResolver() {
        @Override public <T> T resolve(Class<T> type) { return null; }
    };

    @ParameterizedTest
    @EnumSource(Stage.class)
    void applicationExceptionsCrossBlockingAndFailedStageBoundaries(Stage stage) throws Exception {
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(Executors.newSingleThreadExecutor())) {
            for (Mode mode : Mode.values()) {
                var fixture = new Fixture();
                var future = scope.commands(fixture.pipeline).execute(new TestCommand(stage, failure(mode)), options()).toCompletableFuture();
                if (mode == Mode.GETTER_CANCEL || mode == Mode.CANCEL) {
                    assertThrows(CancellationException.class, () -> future.get(5, TimeUnit.SECONDS));
                    assertTrue(future.isCancelled());
                } else {
                    CommandResult<?> result = future.get(5, TimeUnit.SECONDS);
                    assertFalse(result.isSuccess());
                    assertEquals(ID, result.getCorrelationId());
                    assertNull(result.getResponse());
                    if (mode == Mode.VALIDATION) assertValidation(result);
                    else {
                        assertEquals(List.of("ordinary secret"), result.getExceptionMessages());
                        assertTrue(result.getExceptionStackTrace().contains("ordinary secret"));
                        assertTrue(result.getValidationResults().isEmpty());
                    }
                }
                assertEquals(cleanup(stage), fixture.completed);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void ignoredExplicitNestedJavaFailuresStillRollBackRoot(Stage stage) throws Exception {
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(Executors.newSingleThreadExecutor())) {
            for (Mode mode : List.of(Mode.VALIDATION, Mode.GETTER_CANCEL)) {
                var fixture = new Fixture();
                var commands = scope.commands(fixture.pipeline);
                fixture.operation = context -> commands.execute(new TestCommand(stage, failure(mode)), CommandExecutionOptions.nested(context))
                    .handle((result, thrown) -> {
                        if (mode == Mode.VALIDATION) { assertNull(thrown); assertValidation(result); }
                        else { assertNull(result); assertTrue(thrown instanceof CancellationException); }
                        return "discarded";
                    });
                var root = commands.execute(new TestCommand(null, null), options()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertFalse(root.isSuccess());
                assertEquals(List.of("A nested command execution failed; the root execution is rollback-only."), root.getExceptionMessages());
                assertNull(root.getResponse());
                var expected = new ArrayList<>(cleanup(stage));
                expected.addAll(List.of("second", "first"));
                assertEquals(expected, fixture.completed);
                assertTrue(fixture.completions.subList(fixture.completions.size() - 2, fixture.completions.size()).stream()
                    .noneMatch(CommandResult::isSuccess));
            }
        }
    }

    @Test
    void blockingApplicationHandlerAndValidateAreJavaFriendly() throws Exception {
        var registry = new ConcurrentCommandHandlerRegistry();
        registry.register(new BlockingCommandHandlerAdapter(new BlockingCommandHandler() {
            @Override public Class<?> getCommandType() { return TestCommand.class; }
            @Override public CommandDescriptor getMetadata() { return new CommandDescriptor("TestCommand", TestCommand.class.getName()); }
            @Override public Object invoke(CommandContext context) { throw new ApplicationFailure(false); }
        }));
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(Executors.newSingleThreadExecutor())) {
            var commands = scope.commands(new DefaultCommandPipeline(registry));
            assertValidation(commands.execute(new TestCommand(null, null), options()).toCompletableFuture().get(5, TimeUnit.SECONDS));
            for (Stage stage : List.of(Stage.CONTEXT, Stage.FILTER)) {
                var fixture = new Fixture();
                assertValidation(scope.commands(fixture.pipeline).validate(new TestCommand(stage, new ApplicationFailure(false)), options())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertTrue(fixture.completed.isEmpty());
            }
        }
    }

    private static CommandExecutionOptions options() { return new CommandExecutionOptions(ID, ArcPrincipal.anonymous(), NO_SERVICES); }
    private static void assertValidation(CommandResult<?> result) {
        assertFalse(result.isSuccess());
        assertFalse(result.getHasExceptions());
        assertTrue(result.isAuthorized());
        assertEquals(List.of(FEEDBACK), result.getValidationResults());
        assertEquals(List.of(), result.getExceptionMessages());
        assertEquals("", result.getExceptionStackTrace());
        assertNull(result.getResponse());
    }
    private static List<String> cleanup(Stage stage) {
        return stage == Stage.CONTEXT ? List.of() : stage == Stage.BEGIN ? List.of("first") : List.of("second", "first");
    }
    private static RuntimeException failure(Mode mode) {
        return switch (mode) {
            case VALIDATION -> new ApplicationFailure(false);
            case GETTER_CANCEL -> new ApplicationFailure(true);
            case CANCEL -> new CancellationException("direct cancel");
            case ORDINARY -> new IllegalStateException("ordinary secret");
        };
    }
    enum Stage { CONTEXT, BEGIN, FILTER, PREPARE, HANDLE, MATCH, RESPONSE, COMPLETE }
    private enum Mode { VALIDATION, ORDINARY, CANCEL, GETTER_CANCEL }
    private record TestCommand(Stage stage, RuntimeException failure) {
        void visit(Stage current) { if (stage == current) throw failure; }
    }
    private record Response() { }
    private static final class ApplicationFailure extends RuntimeException implements ValidationFailure {
        private static final long serialVersionUID = 1L;
        private final boolean cancel;
        ApplicationFailure(boolean cancel) { super("exception secret"); this.cancel = cancel; }
        @Override public List<ValidationResult> getValidationResults() {
            if (cancel) throw new CancellationException("getter cancel");
            return List.of(FEEDBACK);
        }
    }
    private static final class Fixture {
        final List<String> completed = new ArrayList<>();
        final List<CommandResult<?>> completions = new ArrayList<>();
        Function<CommandContext, CompletionStage<?>> operation = context -> CompletableFuture.completedFuture(new Response());
        final DefaultCommandPipeline pipeline;
        Fixture() {
            var registry = new ConcurrentCommandHandlerRegistry();
            registry.register(new AsyncCommandHandlerAdapter(new AsyncCommandHandler() {
                @Override public Class<?> getCommandType() { return TestCommand.class; }
                @Override public CommandDescriptor getMetadata() { return new CommandDescriptor("TestCommand", TestCommand.class.getName()); }
                @Override public CompletionStage<CommandPreparation> prepare(CommandContext context) {
                    var command = (TestCommand) context.getCommand();
                    return command.stage() == Stage.PREPARE ? CompletableFuture.failedFuture(command.failure())
                        : CompletableFuture.completedFuture(CommandPreparation.empty(context.getCorrelationId()));
                }
                @Override public CompletionStage<?> invoke(CommandContext context) {
                    var command = (TestCommand) context.getCommand();
                    if (command.stage() == Stage.HANDLE) return CompletableFuture.failedFuture(command.failure());
                    return command.stage() == null ? operation.apply(context) : CompletableFuture.completedFuture(new Response());
                }
            }));
            pipeline = new DefaultCommandPipeline(registry,
                List.of(new BlockingCommandFilterAdapter(context -> {
                    ((TestCommand) context.getCommand()).visit(Stage.FILTER);
                    return CommandResult.success(context.getCorrelationId());
                })), List.of("first", "second").stream().map(name -> new AsyncCommandExecutionScopeAdapter(new AsyncCommandExecutionScope() {
                    @Override public void begin(CommandContext context) {
                        if (name.equals("second")) ((TestCommand) context.getCommand()).visit(Stage.BEGIN);
                    }
                    @Override public CompletionStage<CommandResult<?>> complete(CommandContext context, CommandResult<?> result) {
                        completed.add(name); completions.add(result);
                        var command = (TestCommand) context.getCommand();
                        return name.equals("second") && command.stage() == Stage.COMPLETE
                            ? CompletableFuture.failedFuture(command.failure()) : CompletableFuture.completedFuture(null);
                    }
                })).toList(), List.of(new AsyncCommandResponseValueHandlerAdapter(new AsyncCommandResponseValueHandler() {
                    @Override public boolean canHandle(CommandContext context, Object value) {
                        if (!(value instanceof Response)) return false;
                        ((TestCommand) context.getCommand()).visit(Stage.MATCH);
                        return true;
                    }
                    @Override public CompletionStage<CommandResult<?>> handle(CommandContext context, Object value) {
                        var command = (TestCommand) context.getCommand();
                        return command.stage() == Stage.RESPONSE ? CompletableFuture.failedFuture(command.failure())
                            : CompletableFuture.completedFuture(CommandResult.success(context.getCorrelationId()));
                    }
                })), List.of(command -> { ((TestCommand) command).visit(Stage.CONTEXT); return Map.of(); }));
        }
    }
}
