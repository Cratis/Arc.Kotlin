// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing;

import io.cratis.arc.results.CommandResult;
import io.cratis.arc.results.QueryResult;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.testing.java.AsyncCommandScenario;
import io.cratis.arc.testing.java.AsyncQueryScenario;
import io.cratis.arc.testing.java.BlockingCommandScenario;
import io.cratis.arc.testing.java.BlockingQueryScenario;
import io.cratis.arc.tenancy.HeaderTenantIdResolver;
import io.cratis.arc.tenancy.TenantResolutionContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    void positiveAuthorizationValidityAndErrorAssertionsAreJavaFriendly() {
        UUID correlationId = UUID.randomUUID();
        CommandScenarioResult<Void> rejectedByValidation = new CommandScenarioResult<>(
            CommandResult.invalid(correlationId, List.of(ValidationResult.error("rejected", List.of("value")))));
        CommandScenarioResult<Void> failedWithException = new CommandScenarioResult<>(
            CommandResult.error(correlationId, "handler exploded"));
        QueryScenarioResult<TestModel> readyQuery = new QueryScenarioResult<>(
            QueryResult.success(correlationId, new TestModel("data")));

        rejectedByValidation.shouldBeAuthorized().shouldBeInvalid().shouldHaveNoErrors();
        failedWithException.shouldBeAuthorized().shouldBeValid().shouldHaveErrors();
        readyQuery.shouldBeAuthorized().shouldBeValid();

        assertTrue(
            assertThrows(AssertionError.class, rejectedByValidation::shouldBeValid)
                .getMessage().contains("Expected the command to be valid"));
        assertTrue(
            assertThrows(AssertionError.class, failedWithException::shouldHaveNoErrors)
                .getMessage().contains("Expected the command to have no errors"));
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
