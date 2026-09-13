// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.contracts.fixtures.FixtureCircle;
import io.cratis.arc.contracts.fixtures.JavaFixtureImplementation;
import io.cratis.arc.contracts.fixtures.ScenarioShapeCommand;
import io.cratis.arc.contracts.fixtures.ScenarioShapeSource;
import io.cratis.arc.contracts.fixtures.ScenarioShapeView;
import io.cratis.arc.generated.ContractTestsArcArtifactModule;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.testing.CommandScenario;
import io.cratis.arc.testing.QueryScenario;
import io.cratis.arc.testing.java.AsyncCommandScenario;
import io.cratis.arc.testing.java.AsyncQueryScenario;
import io.cratis.arc.testing.java.BlockingCommandScenario;
import io.cratis.arc.testing.java.BlockingQueryScenario;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.Dispatchers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** Real Java callers retain default JSON round trips over generated module registrations. */
final class GeneratedScenarioDerivedTypesJavaContractTest {
    private final ContractTestsArcArtifactModule module = new ContractTestsArcArtifactModule();
    private final FixtureCircle shape = new FixtureCircle("java", 3.0, Instant.parse("2025-01-02T03:04:05Z"));
    private final JavaFixtureImplementation implementation = new JavaFixtureImplementation("record");
    private final ScenarioShapeView view = new ScenarioShapeView(shape, shape, implementation);

    @Test
    void blockingBridgesRoundTripGeneratedPolymorphicArtifacts() {
        try (BlockingCommandScenario<ScenarioShapeCommand> commands = new BlockingCommandScenario<>(command());
             BlockingQueryScenario<ScenarioShapeView> queries = new BlockingQueryScenario<>(query())) {
            assertCopied(commands.execute(new ScenarioShapeCommand(shape, shape, implementation))
                .shouldSucceed().shouldHaveResponse(ScenarioShapeView.class));
            assertCopied(queries.perform().shouldSucceed().shouldHaveData(ScenarioShapeView.class));
        }
    }

    @Test
    void completionStageBridgesRoundTripGeneratedPolymorphicArtifacts() throws Exception {
        CoroutineScope scope = CoroutineScopeKt.CoroutineScope(Dispatchers.getDefault());
        try {
            AsyncCommandScenario<ScenarioShapeCommand> commands = new AsyncCommandScenario<>(command(), scope);
            AsyncQueryScenario<ScenarioShapeView> queries = new AsyncQueryScenario<>(query(), scope);
            assertCopied(commands.execute(new ScenarioShapeCommand(shape, shape, implementation))
                .toCompletableFuture().get(5, TimeUnit.SECONDS)
                .shouldSucceed().shouldHaveResponse(ScenarioShapeView.class));
            assertCopied(queries.perform().toCompletableFuture().get(5, TimeUnit.SECONDS)
                .shouldSucceed().shouldHaveData(ScenarioShapeView.class));
        } finally {
            CoroutineScopeKt.cancel(scope, null);
        }
    }

    private CommandScenario<ScenarioShapeCommand> command() {
        return new CommandScenario<>(module, ScenarioShapeCommand.class)
            .withPrincipal(new ArcPrincipal("tester", true));
    }

    private QueryScenario<ScenarioShapeView> query() {
        return new QueryScenario<ScenarioShapeView>(module,
            new FullyQualifiedQueryName(ScenarioShapeView.class.getName() + ".nested"))
            .withPrincipal(new ArcPrincipal("tester", true))
            .addService(ScenarioShapeSource.class, new ScenarioShapeSource(view));
    }

    private void assertCopied(ScenarioShapeView actual) {
        assertEquals(view, actual);
        assertNotSame(shape, actual.getShape());
        assertNotSame(shape, actual.getBase());
        assertNotSame(implementation, actual.getJavaContract());
    }
}
