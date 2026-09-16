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
import io.cratis.arc.java.AsyncModelValidator;
import io.cratis.arc.java.AsyncModelValidatorAdapter;
import io.cratis.arc.java.BlockingCommandHandler;
import io.cratis.arc.java.BlockingCommandHandlerAdapter;
import io.cratis.arc.java.BlockingModelValidator;
import io.cratis.arc.java.BlockingModelValidatorAdapter;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.queries.DefaultQueryValidationFilter;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.results.CommandResult;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.results.ValidationResultReasons;
import io.cratis.arc.validation.ModelValidationContext;
import io.cratis.arc.validation.ModelValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ModelValidationJavaConformanceTest {
    enum Feedback { NULL_LIST, NULL_ENTRY, WRONG_TYPE, CHECKED_STAGE_FAILURE }
    public record Input(String name) { }
    public record Command(Input input) { }
    private static final ServiceResolver SERVICES = new ServiceResolver() {
        @Override public <T> T resolve(Class<T> type) { return null; }
    };
    private static final CommandExecutionOptions OPTIONS = new CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), SERVICES);

    @Test
    void oldAndAdditiveConstructorsAndBothOperationContextsAreOrdinaryJava() throws Exception {
        var commands = List.of(new DefaultCommandValidationFilter(List.of()), new DefaultCommandValidationFilter(List.of(), List.of()),
            new DefaultCommandValidationFilter(List.of(), List.of(), List.of()));
        var queries = List.of(new DefaultQueryValidationFilter(List.of()), new DefaultQueryValidationFilter(List.of(), List.of()),
            new DefaultQueryValidationFilter(List.of(), List.of(), List.of()));
        assertEquals(3, queries.size());
        try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(Runnable::run)) {
            for (var filter : commands) {
                assertTrue(scope.commands(pipeline(filter)).execute(new Command(new Input("valid")), OPTIONS)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS).isSuccess());
            }
        }
        var command = new CommandContext(OPTIONS.getCorrelationId(), new Command(new Input("")), Command.class, OPTIONS.getPrincipal(), SERVICES);
        var commandNode = new ModelValidationContext(command, "input");
        assertSame(command, commandNode.getCommandContext());
        assertNull(commandNode.getQueryContext());
        assertEquals("input", commandNode.getMemberPath());
        assertSame(command.getPrincipal(), commandNode.getPrincipal());
        assertSame(SERVICES, commandNode.getServiceResolver());
        assertEquals(command.getCorrelationId(), commandNode.getCorrelationId());
        assertNull(commandNode.getTenantId());
        assertNull(commandNode.getTenantNamespace());
        var request = new QueryRequest(new FullyQualifiedQueryName("model.query"), Map.of());
        var query = new QueryContext(UUID.randomUUID(), request, request.getQueryName(), ArcPrincipal.anonymous(), "tenant", "namespace", SERVICES, null, false);
        var queryNode = new ModelValidationContext(query, "arg");
        assertSame(query, queryNode.getQueryContext());
        assertNull(queryNode.getCommandContext());
        assertSame(SERVICES, queryNode.getServiceResolver());
        assertEquals(query.getCorrelationId(), queryNode.getCorrelationId());
        assertEquals("tenant", queryNode.getTenantId());
        assertEquals("namespace", queryNode.getTenantNamespace());
    }

    @Test
    void blockingAndCompletionStageAdaptersPreserveRecordPathsAndSnapshotMutableFeedback() throws Exception {
        var feedback = new ArrayList<>(List.of(ValidationResult.error("first", List.of("name"))));
        var blocking = new BlockingModelValidatorAdapter<>(new BlockingModelValidator<Input>() {
            @Override public Class<Input> getModelType() { return Input.class; }
            @Override public List<ValidationResult> validate(Input input, ModelValidationContext context) {
                assertEquals("input", context.getMemberPath());
                return feedback;
            }
        });
        var async = async(input -> { feedback.clear(); return CompletableFuture.completedFuture(List.of(ValidationResult.error("second"))); });
        var result = execute(List.of(blocking, async));
        assertEquals(List.of("first", "second"), result.getValidationResults().stream().map(ValidationResult::getMessage).toList());
        assertEquals(List.of("input.name", "input"), result.getValidationResults().stream().flatMap(value -> value.getMembers().stream()).toList());
        assertTrue(execute(List.of(async(input -> CompletableFuture.completedFuture(List.of())))).isSuccess());
    }

    @ParameterizedTest
    @EnumSource(Feedback.class)
    void malformedJavaFeedbackAndOrdinaryCheckedStageFailureBecomeSafeNodeFeedback(Feedback shape) throws Exception {
        List<ValidationResult> returned = new ArrayList<>();
        returned.add(ValidationResult.error("must not leak from malformed list"));
        if (shape == Feedback.NULL_ENTRY) returned.add(null);
        if (shape == Feedback.WRONG_TYPE) ArrayList.class.getMethod("add", Object.class).invoke(returned, "wrong type");
        var validator = async(input -> switch (shape) {
            case NULL_LIST -> CompletableFuture.completedFuture(null);
            case NULL_ENTRY, WRONG_TYPE -> CompletableFuture.completedFuture(returned);
            case CHECKED_STAGE_FAILURE -> CompletableFuture.failedFuture(new Exception("secret checked exception"));
        });
        var result = execute(List.of(validator));
        assertFalse(result.isSuccess());
        assertFalse(result.getHasExceptions());
        var feedback = result.getValidationResults();
        assertEquals(1, feedback.size());
        assertEquals(ValidationResultReasons.VALIDATOR_FAILED, feedback.get(0).getReason());
        assertEquals("The value could not be validated.", feedback.get(0).getMessage());
        assertEquals(List.of("input"), feedback.get(0).getMembers());
        assertNull(feedback.get(0).getState());
        assertNull(feedback.get(0).getReasonDetail());
    }

    @Test
    void blockingNullFeedbackAlsoFailsSafely() throws Exception {
        var validator = new BlockingModelValidatorAdapter<>(new BlockingModelValidator<Input>() {
            @Override public Class<Input> getModelType() { return Input.class; }
            @Override public List<ValidationResult> validate(Input input, ModelValidationContext context) { return null; }
        });
        assertEquals(ValidationResultReasons.VALIDATOR_FAILED, execute(List.of(validator)).getValidationResults().get(0).getReason());
    }

    @Test
    void failedStageCancellationAndCancellingPendingValidationPropagate() {
        try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(Runnable::run)) {
            var cancelled = scope.commands(pipeline(new DefaultCommandValidationFilter(List.of(), List.of(),
                List.of(async(input -> CompletableFuture.failedFuture(new CancellationException("cancelled")))))))
                .execute(new Command(new Input("")), OPTIONS).toCompletableFuture();
            assertThrows(CancellationException.class, () -> cancelled.get(5, TimeUnit.SECONDS));
            assertTrue(cancelled.isCancelled());
            var pending = new CompletableFuture<List<ValidationResult>>();
            var result = scope.commands(pipeline(new DefaultCommandValidationFilter(List.of(), List.of(), List.of(async(input -> pending)))))
                .execute(new Command(new Input("")), OPTIONS).toCompletableFuture();
            assertFalse(result.isDone());
            result.cancel(true);
            assertTrue(pending.isCancelled());
        }
    }

    private static ModelValidator<Input> async(Function<Input, CompletionStage<List<ValidationResult>>> validation) {
        return new AsyncModelValidatorAdapter<>(new AsyncModelValidator<Input>() {
            @Override public Class<Input> getModelType() { return Input.class; }
            @Override public CompletionStage<List<ValidationResult>> validate(Input input, ModelValidationContext context) { return validation.apply(input); }
        });
    }
    private static CommandResult<?> execute(List<ModelValidator<?>> validators) throws Exception {
        try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(Runnable::run)) {
            return scope.commands(pipeline(new DefaultCommandValidationFilter(List.of(), List.of(), validators)))
                .execute(new Command(new Input("")), OPTIONS).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
    private static DefaultCommandPipeline pipeline(DefaultCommandValidationFilter filter) {
        var handlers = new ConcurrentCommandHandlerRegistry();
        handlers.register(new BlockingCommandHandlerAdapter(new BlockingCommandHandler() {
            @Override public Class<?> getCommandType() { return Command.class; }
            @Override public CommandDescriptor getMetadata() { return new CommandDescriptor("Command", Command.class.getName()); }
            @Override public Object invoke(CommandContext context) { return "handled"; }
        }));
        return new DefaultCommandPipeline(handlers, List.of(filter));
    }
}
