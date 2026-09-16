// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts;

import io.cratis.arc.contracts.fixtures.JavaCustomerCode;
import io.cratis.arc.contracts.fixtures.JavaExclusionCommand;
import io.cratis.arc.contracts.fixtures.JavaExclusionInput;
import io.cratis.arc.generated.ContractTestsArcArtifactModule;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.testing.CommandScenario;
import io.cratis.arc.testing.ObservableQueryScenario;
import io.cratis.arc.testing.QueryScenario;
import io.cratis.arc.testing.java.AsyncObservableQueryScenario;
import io.cratis.arc.testing.java.BlockingCommandScenario;
import io.cratis.arc.testing.java.BlockingQueryScenario;
import io.cratis.arc.validation.ConceptValidationExclusion;
import io.cratis.arc.validation.ConceptValidator;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class GeneratedConceptExclusionScenarioJavaContractTest {
    private final ContractTestsArcArtifactModule module = new ContractTestsArcArtifactModule();
    private final ConceptValidationExclusion exclusion = new ConceptValidationExclusion(JavaExclusionInput.class, "ignored");
    private final ConceptValidator<JavaCustomerCode> concept = new ConceptValidator<>() {
        @Override public Class<JavaCustomerCode> getConceptType() { return JavaCustomerCode.class; }
        @Override public List<ValidationResult> validate(JavaCustomerCode value) {
            return value.value().equals("RULE") ? List.of(ValidationResult.error("concept")) : List.of();
        }
    };

    @Test
    void javaConfiguresAllThreeGeneratedScenariosWithOrdinaryRegistrationAndExistingBridges() throws Exception {
        var commands = new CommandScenario<>(module, JavaExclusionCommand.class).addConceptValidator(concept);
        var queries = new QueryScenario<Object>(module, name("findJavaExclusions")).addConceptValidator(concept);
        var observables = new ObservableQueryScenario<Object>(module, name("observeJavaExclusions")).addConceptValidator(concept);
        assertSame(commands, commands.addConceptExclusion(exclusion));
        assertSame(queries, queries.addConceptExclusion(exclusion));
        assertSame(observables, observables.addConceptExclusion(exclusion));
        var shared = new JavaCustomerCode("RULE");
        var input = new JavaExclusionInput(shared, shared);
        try (var command = new BlockingCommandScenario<>(commands); var query = new BlockingQueryScenario<>(queries);
             var scope = JavaAsyncScope.usingExecutor(Runnable::run)) {
            for (boolean roundTrip : List.of(true, false)) {
                commands.withSerializationRoundTrip(roundTrip);
                queries.withArgumentSerializationRoundTrip(roundTrip);
                assertEquals(List.of("input.required"), command.execute(new JavaExclusionCommand(input)).getResult().getValidationResults().get(0).getMembers());
                assertEquals(List.of("input.required"), command.validate(new JavaExclusionCommand(input)).getResult().getValidationResults().get(0).getMembers());
                assertEquals(List.of("input.required"), query.perform(Map.of("input", input)).getResult().getValidationResults().get(0).getMembers());
                command.execute(new JavaExclusionCommand(new JavaExclusionInput(shared, null))).shouldSucceed();
                query.perform(Map.of("input", new JavaExclusionInput(shared, null))).shouldSucceed();
            }
            query.perform(Collections.singletonMap("input", null)).shouldSucceed();
            var observable = new AsyncObservableQueryScenario<>(observables, scope);
            var invalid = observable.collectAsync(1, 5000, Map.of("input", input)).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(List.of("input.required"), invalid.shouldFail().getValidationResults().get(0).getMembers());
            observable.collectAsync(1, 5000, Map.of("input", new JavaExclusionInput(shared, null))).toCompletableFuture().get(5, TimeUnit.SECONDS)
                .shouldSucceed().shouldHaveEmissionCount(1);
            observable.collectAsync(1, 5000, Collections.singletonMap("input", null)).toCompletableFuture().get(5, TimeUnit.SECONDS)
                .shouldSucceed().shouldHaveEmissionCount(1);
        }
    }
    private static FullyQualifiedQueryName name(String method) { return new FullyQualifiedQueryName("io.cratis.arc.contracts.fixtures.JavaExclusionView." + method); }
}
