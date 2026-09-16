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
import io.cratis.arc.java.BlockingCommandHandler;
import io.cratis.arc.java.BlockingCommandHandlerAdapter;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.queries.DefaultQueryValidationFilter;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.validation.ConceptValidationExclusion;
import io.cratis.arc.validation.ConceptValidator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConceptExclusionJavaConformanceTest {
    public record Code(String value) implements ConceptAs<String> { }
    public record Input(Code ignored, Code required) { }
    public record Command(Input input) { }
    public static class Fields { public Code code = new Code(""); }
    public static final class InheritedFields extends Fields { }
    public record Trap(Code code) { @Override public Code code() { throw new AssertionError("Do not invoke accessor during registration"); } }
    public record Invalid(Object code, List<Code> codes) { }
    private static final ServiceResolver SERVICES = new ServiceResolver() {
        @Override public <T> T resolve(Class<T> type) { return null; }
    };
    private final ConceptValidator<Code> validator = new ConceptValidator<>() {
        @Override public Class<Code> getConceptType() { return Code.class; }
        @Override public List<ValidationResult> validate(Code value) { return List.of(ValidationResult.error("concept")); }
    };

    @Test
    void javaRegistersRecordsAndFieldsWithOrdinaryConstructorsAndGetters() {
        var exclusion = new ConceptValidationExclusion(Input.class, "ignored");
        assertEquals(Input.class, exclusion.getOwnerType());
        assertEquals("ignored", exclusion.getMember());
        assertEquals("code", new ConceptValidationExclusion(Trap.class, "code").getMember());
        assertEquals(Fields.class, new ConceptValidationExclusion(Fields.class, "code").getOwnerType());
        assertEquals(InheritedFields.class, new ConceptValidationExclusion(InheritedFields.class, "code").getOwnerType());
        assertThrows(IllegalArgumentException.class, () -> new ConceptValidationExclusion(Invalid.class, "code"));
        assertThrows(IllegalArgumentException.class, () -> new ConceptValidationExclusion(Invalid.class, "codes"));
        assertThrows(IllegalArgumentException.class, () -> new ConceptValidationExclusion(Input.class, "ignored.value"));
        assertThrows(IllegalArgumentException.class, () -> new ConceptValidationExclusion(Input.class, " "));
        var queries = new DefaultQueryValidationFilter(List.of(), List.of(validator), List.of(), List.of(exclusion));
        assertEquals(DefaultQueryValidationFilter.class, queries.getClass());
    }

    @Test
    void actualSharedAndEqualRecordIdentitiesUseRequiredPathInEitherOrder() throws Exception {
        var shared = new Code("");
        for (Code other : List.of(shared, new Code(""))) {
            assertEquals(List.of("input.required"), execute(new Command(new Input(shared, other)), "ignored"));
            assertEquals(List.of("input.ignored"), execute(new Command(new Input(shared, other)), "required"));
        }
    }

    @Test
    void publicFieldsAreActuallyExcludedWithoutExpandingBaseOwnerMatching() throws Exception {
        var fields = new Fields();
        assertTrue(executeValue(fields, new ConceptValidationExclusion(Fields.class, "code")).isEmpty());
        assertEquals(List.of("code"), executeValue(new InheritedFields(), new ConceptValidationExclusion(Fields.class, "code")));
        assertTrue(executeValue(new InheritedFields(), new ConceptValidationExclusion(InheritedFields.class, "code")).isEmpty());
    }

    private List<String> execute(Command value, String member) throws Exception {
        return executeValue(value, new ConceptValidationExclusion(Input.class, member));
    }
    private List<String> executeValue(Object value, ConceptValidationExclusion exclusion) throws Exception {
        var handlers = new ConcurrentCommandHandlerRegistry();
        handlers.register(new BlockingCommandHandlerAdapter(new BlockingCommandHandler() {
            @Override public Class<?> getCommandType() { return value.getClass(); }
            @Override public CommandDescriptor getMetadata() { return new CommandDescriptor("Command", value.getClass().getName()); }
            @Override public Object invoke(CommandContext context) { return "handled"; }
        }));
        var filter = new DefaultCommandValidationFilter(List.of(), List.of(validator), List.of(), List.of(exclusion));
        var pipeline = new DefaultCommandPipeline(handlers, List.of(filter));
        var options = new CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), SERVICES);
        try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(Runnable::run)) {
            return scope.commands(pipeline).execute(value, options).toCompletableFuture().get(5, TimeUnit.SECONDS)
                .getValidationResults().stream().flatMap(result -> result.getMembers().stream()).toList();
        }
    }
}
