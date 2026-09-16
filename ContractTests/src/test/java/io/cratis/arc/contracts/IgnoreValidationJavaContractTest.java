// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts;

import io.cratis.arc.contracts.fixtures.JavaFluentContractInput;
import io.cratis.arc.contracts.fixtures.JavaIgnoreContractInput;
import io.cratis.arc.contracts.fixtures.JavaIgnoreContractCommand;
import io.cratis.arc.contracts.fixtures.JavaIgnoreContractRules;
import io.cratis.arc.contracts.fixtures.JavaIgnoreContractView;
import io.cratis.arc.generated.ContractTestsArcArtifactModule;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.testing.java.BlockingCommandScenario;
import io.cratis.arc.testing.java.BlockingQueryScenario;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IgnoreValidationJavaContractTest {
    @Test
    void generatedJavaCommandQueryAndDirectDeclarationKeepActiveValidation() {
        var module = new ContractTestsArcArtifactModule();
        var ignored = new JavaFluentContractInput("too long");
        var accepted = new JavaIgnoreContractInput("", ignored, List.of(ignored), new JavaFluentContractInput("ok"), "ok");
        var invalid = new JavaIgnoreContractInput("", ignored, List.of(ignored), ignored, "");
        try (var command = new BlockingCommandScenario<>(module, JavaIgnoreContractCommand.class);
             var query = new BlockingQueryScenario<JavaIgnoreContractView>(module,
                 new FullyQualifiedQueryName(JavaIgnoreContractView.class.getName() + ".checkJavaIgnoredContract"))) {
            command.execute(new JavaIgnoreContractCommand(accepted)).shouldSucceed();
            query.perform(Map.of("input", accepted)).shouldSucceed();
            var expected = List.of(List.of("input.sibling"), List.of("input.validated.name"));
            assertEquals(expected, command.execute(new JavaIgnoreContractCommand(invalid)).getResult().getValidationResults().stream().map(v -> v.getMembers()).toList());
            assertEquals(expected, query.perform(Map.of("input", invalid)).getResult().getValidationResults().stream().map(v -> v.getMembers()).toList());
        }
        var rules = new JavaIgnoreContractRules();
        assertTrue(rules.validate(accepted).isEmpty());
        assertEquals(List.of("ignored", "ignoredList", "sibling"), rules.getRules().stream().map(r -> r.getMember()).toList());
        assertTrue(module.getTypes().stream().filter(t -> t.getFullyQualifiedName().equals(JavaIgnoreContractInput.class.getName()))
            .flatMap(t -> t.getProperties().stream()).filter(p -> p.getName().startsWith("ignored")).allMatch(p -> p.getIgnoreValidation()));
    }
}
