// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing;

import io.cratis.arc.results.ValidationResultReasons;
import io.cratis.arc.testing.java.AsyncCommandScenario;
import io.cratis.arc.testing.java.AsyncQueryScenario;
import io.cratis.arc.testing.java.BlockingCommandScenario;
import io.cratis.arc.testing.java.BlockingQueryScenario;
import io.cratis.arc.tenancy.HeaderTenantIdResolver;
import io.cratis.arc.tenancy.TenantResolutionContext;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.Dispatchers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compile-time and runtime contracts for first-class Java scenario use. */
final class JavaScenarioConformanceTest {
    @Test
    void immutableServicesAndBlockingBridgesAreJavaFriendly() {
        ScenarioServiceResolver services = ScenarioServiceResolver.builder()
            .put(TestDependency.class, new TestDependency("java"))
            .build();
        assertEquals("java", services.resolve(TestDependency.class).getPrefix());
        assertThrows(
            IllegalArgumentException.class,
            () -> services.put(TestDependency.class, new TestDependency("duplicate")));

        ManualCommandHandler handler = new ManualCommandHandler();
        ManualArtifactModule module = new ManualArtifactModule(handler, new ManualQueryPerformer());
        try (BlockingCommandScenario<TestCommand> commands =
                 new BlockingCommandScenario<>(module, TestCommand.class)) {
            TestResponse response = commands.execute(new TestCommand("command"))
                .shouldSucceed()
                .shouldHaveResponse(TestResponse.class);
            assertEquals("command", response.getValue());
            commands.validate(new TestCommand("validate")).shouldSucceed();
            assertEquals(1, handler.getInvocationCount());
        }

        try (BlockingQueryScenario<TestModel> queries = new BlockingQueryScenario<>(new ManualQueryPerformer())) {
            queries.perform(Map.of()).shouldSucceed().shouldHaveData(TestModel.class);
        }

        ManualCommandHandler tenantHandler = new ManualCommandHandler();
        CommandScenario<TestCommand> tenantScenario = new CommandScenario<TestCommand>(tenantHandler)
            .withTenantResolution(
                new HeaderTenantIdResolver(),
                new TenantResolutionContext(Map.of("X-Cratis-Tenant-Id", "java-tenant")));
        try (BlockingCommandScenario<TestCommand> commands = new BlockingCommandScenario<>(tenantScenario)) {
            commands.execute(new TestCommand("tenant")).shouldSucceed();
        }
        assertEquals("java-tenant", tenantHandler.getLastContext().getTenantId());
    }

    @Test
    void pinnedReadModelsAreConfigurableAndObservableFromJava() {
        CommandScenario<KeyedTestCommand> pinned =
            new CommandScenario<KeyedTestCommand>(new ReadModelCommandHandler())
                .withReadModelForKey(TestModel.class, "model-1", new TestModel("pinned"));
        try (BlockingCommandScenario<KeyedTestCommand> scenario = new BlockingCommandScenario<>(pinned)) {
            TestResponse response = scenario.execute(new KeyedTestCommand(new TestModelId("model-1"), "value"))
                .shouldSucceed()
                .shouldHaveResponse(TestResponse.class);
            assertEquals("pinned", response.getValue());
        }

        CommandScenario<KeyedTestCommand> empty =
            new CommandScenario<KeyedTestCommand>(new ReadModelCommandHandler(TestReadModelArgument.OPTIONAL))
                .withReadModel(TestModel.class, null);
        try (BlockingCommandScenario<KeyedTestCommand> scenario = new BlockingCommandScenario<>(empty)) {
            TestResponse response = scenario.execute(new KeyedTestCommand(new TestModelId("model-1"), "value"))
                .shouldSucceed()
                .shouldHaveResponse(TestResponse.class);
            assertEquals("absent", response.getValue());
        }

        CommandScenario<KeyedTestCommand> required =
            new CommandScenario<KeyedTestCommand>(new ReadModelCommandHandler())
                .withReadModelForKey(TestModel.class, "other-key", new TestModel("pinned"));
        try (BlockingCommandScenario<KeyedTestCommand> scenario = new BlockingCommandScenario<>(required)) {
            assertEquals(
                ValidationResultReasons.DEPENDENCY_UNAVAILABLE,
                scenario.execute(new KeyedTestCommand(new TestModelId("model-1"), "value"))
                    .shouldBeInvalid()
                    .getResult()
                    .getValidationResults()
                    .get(0)
                    .getReason());
        }

        assertThrows(
            IllegalArgumentException.class,
            () -> pinned.withReadModel(TestModel.class, new TestModel("duplicate")));
    }

    @Test
    void completionStageBridgesExecuteWithCallerOwnedScope() throws Exception {
        CoroutineScope scope = CoroutineScopeKt.CoroutineScope(Dispatchers.getDefault());
        AsyncCommandScenario<TestCommand> commands = new AsyncCommandScenario<>(new ManualCommandHandler(), scope);
        AsyncQueryScenario<TestModel> queries = new AsyncQueryScenario<>(new ManualQueryPerformer(), scope);

        assertTrue(
            commands.execute(new TestCommand("async")).toCompletableFuture().get(5, TimeUnit.SECONDS)
                .getResult().isSuccess());
        assertEquals(
            new TestModel("default"),
            queries.perform(Map.of()).toCompletableFuture().get(5, TimeUnit.SECONDS).getResult().getData());
    }
}
