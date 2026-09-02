// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle;

import io.cratis.arc.testing.CommandScenario;
import io.cratis.arc.testing.CommandScenarioResult;
import io.cratis.arc.testing.java.BlockingCommandScenario;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Compile-time and runtime contracts for Java Chronicle scenario use. */
final class ChronicleCommandScenarioJavaConformanceTest {
    @Test
    void buildersAndAssertionsAreJavaFriendly() {
        CommandScenario<ScenarioCommand> configured =
            new CommandScenario<ScenarioCommand>(
                new ScenarioCommandHandler(List.<Object>of(new ScenarioEvent("appended"))))
                .withSerializationRoundTrip(false);
        ChronicleCommandScenario chronicle = ChronicleCommandScenarios.chronicle(configured);
        chronicle.given()
            .forEventSource("source")
            .events(new ScenarioOtherEvent("given"))
            .and();

        try (BlockingCommandScenario<ScenarioCommand> scenario = new BlockingCommandScenario<>(configured)) {
            scenario.execute(new ScenarioCommand("source")).shouldSucceed();
        }

        assertEquals(1, chronicle.getAppendedEvents().size());
        assertEquals(
            "appended",
            chronicle.shouldHaveAppendedEvent("source", ScenarioEvent.class).getValue());
    }

    @Test
    void resultFailureAssertionsAreJavaFriendly() {
        CommandScenario<ScenarioCommand> configured =
            new CommandScenario<ScenarioCommand>(
                new ScenarioCommandHandler(List.<Object>of(new ScenarioEvent("rejected"))))
                .withSerializationRoundTrip(false);
        ChronicleCommandScenarios.givenChronicle(configured)
            .constraintViolation("unique-name", "The name is already used.");

        CommandScenarioResult<?> result;
        try (BlockingCommandScenario<ScenarioCommand> scenario = new BlockingCommandScenario<>(configured)) {
            result = scenario.execute(new ScenarioCommand("source"));
        }

        assertEquals(
            "unique-name",
            ChronicleCommandScenarios.shouldHaveConstraintViolation(result, "unique-name").getReasonDetail());
        ChronicleCommandScenarios.chronicle(configured).shouldHaveNoAppendedEvents();
    }
}
