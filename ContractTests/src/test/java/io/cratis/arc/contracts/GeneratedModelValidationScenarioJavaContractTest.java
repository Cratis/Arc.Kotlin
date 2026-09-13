// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.contracts.fixtures.JavaMapMetadataCommand;
import io.cratis.arc.contracts.fixtures.JavaOrderId;
import io.cratis.arc.contracts.fixtures.JavaQueryDependency;
import io.cratis.arc.generated.ContractTestsArcArtifactModule;
import io.cratis.arc.java.BlockingModelValidator;
import io.cratis.arc.java.BlockingModelValidatorAdapter;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.testing.CommandScenario;
import io.cratis.arc.testing.ObservableQueryScenario;
import io.cratis.arc.testing.QueryScenario;
import io.cratis.arc.testing.java.AsyncObservableQueryScenario;
import io.cratis.arc.testing.java.BlockingCommandScenario;
import io.cratis.arc.testing.java.BlockingQueryScenario;
import io.cratis.arc.validation.ConceptValidator;
import io.cratis.arc.validation.ModelValidationContext;
import io.cratis.arc.validation.ModelValidator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GeneratedModelValidationScenarioJavaContractTest {
    private final ContractTestsArcArtifactModule module = new ContractTestsArcArtifactModule();
    private final ModelValidator<String> rule = new BlockingModelValidatorAdapter<>(new BlockingModelValidator<String>() {
        @Override public Class<String> getModelType() { return String.class; }
        @Override public List<ValidationResult> validate(String value, ModelValidationContext context) {
            return value.equals("invalid") ? List.of(ValidationResult.error("model")) : List.of();
        }
    });
    private final ConceptValidator<JavaOrderId> concept = new ConceptValidator<>() {
        @Override public Class<JavaOrderId> getConceptType() { return JavaOrderId.class; }
        @Override public List<ValidationResult> validate(JavaOrderId value) { return List.of(ValidationResult.error("concept", List.of("value"))); }
    };

    @Test
    void javaConfiguresGeneratedCommandAndQueryScenariosWithoutContinuations() {
        var command = new CommandScenario<>(module, JavaMapMetadataCommand.class).withPrincipal(new ArcPrincipal("tester", true))
            .addModelValidator(rule).addConceptValidator(concept);
        var query = new QueryScenario<Object>(module, name("JavaQueryReadModel.contextualJava"))
            .addModelValidator(rule).addConceptValidator(concept);
        try (var commands = new BlockingCommandScenario<>(command); var queries = new BlockingQueryScenario<>(query)) {
            var invalid = new JavaMapMetadataCommand(Map.of("entry", "invalid"), Map.of(), Map.of(), null);
            assertEquals(List.of("strings.entry"), commands.execute(invalid).getResult().getValidationResults().get(0).getMembers());
            assertEquals(List.of("strings.entry"), commands.validate(invalid).getResult().getValidationResults().get(0).getMembers());
            commands.execute(new JavaMapMetadataCommand()).shouldSucceed();
            assertEquals(List.of("label"), queries.perform(Map.of("label", "invalid")).getResult().getValidationResults().get(0).getMembers());
            queries.perform(Map.of("label", "valid")).shouldSucceed();
        }
        var conceptQuery = new QueryScenario<Object>(module, name("ConceptTemporalReadModel.findConceptTemporal"))
            .withPrincipal(new ArcPrincipal("tester", true)).addConceptValidator(concept).addModelValidator(rule);
        try (var queries = new BlockingQueryScenario<>(conceptQuery)) {
            assertEquals(List.of("javaIdentifier"), queries.perform(Map.of("javaIdentifier", new JavaOrderId(UUID.randomUUID())))
                .getResult().getValidationResults().get(0).getMembers());
        }
    }

    @Test
    void javaConfiguresGeneratedObservableScenariosWithBothRegistrationMethods() throws Exception {
        var dependency = new JavaQueryDependency();
        var scenario = new ObservableQueryScenario<Object>(module, name("JavaQueryReadModel.observeJava"))
            .addService(JavaQueryDependency.class, dependency).addModelValidator(rule).addConceptValidator(concept);
        try (JavaAsyncScope owner = JavaAsyncScope.usingExecutor(Runnable::run)) {
            var observable = new AsyncObservableQueryScenario<>(scenario, owner);
            var rejected = observable.collectAsync(1, 5000, Map.of("label", "invalid")).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(List.of("label"), rejected.shouldFail().getValidationResults().get(0).getMembers());
            assertEquals(0, dependency.getInvocationCount());
            observable.collectAsync(1, 5000, Map.of("label", "valid")).toCompletableFuture().get(5, TimeUnit.SECONDS)
                .shouldSucceed().shouldHaveEmissionCount(1);
            assertEquals(1, dependency.getInvocationCount());
            var conceptScenario = new ObservableQueryScenario<Object>(module, name("KotlinObservableSnapshot.observeKotlinSnapshot"))
                .addConceptValidator(concept).addModelValidator(rule);
            var rejectedConcept = new AsyncObservableQueryScenario<>(conceptScenario, owner)
                .collectAsync(1, 5000, Map.of("concept", new JavaOrderId(UUID.randomUUID()))).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(List.of("concept"), rejectedConcept.shouldFail().getValidationResults().get(0).getMembers());
            assertTrue(rejectedConcept.getEmissions().isEmpty());
        }
    }
    private static FullyQualifiedQueryName name(String value) { return new FullyQualifiedQueryName("io.cratis.arc.contracts.fixtures." + value); }
}
