// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javachronicle;

import io.cratis.arc.chronicle.ChronicleCommandScenarios;
import io.cratis.arc.generated.JavaChronicleSpringBootSampleArcArtifactModule;
import io.cratis.arc.queries.BlockingReadModelForCommandResolver;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.ReadModelForCommandOwnership;
import io.cratis.arc.testing.CommandScenario;
import io.cratis.arc.testing.QueryScenario;
import io.cratis.arc.testing.java.BlockingCommandScenario;
import io.cratis.arc.testing.java.BlockingQueryScenario;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Fast public-path contracts for the ordinary-Java Chronicle sample. */
final class JavaChronicleContractsTests {
    private final JavaChronicleSpringBootSampleArcArtifactModule module =
        new JavaChronicleSpringBootSampleArcArtifactModule();

    @Test
    void createReturnsAServerHandledChronicleEvent() {
        var configured = new CommandScenario<>(module, CreateTask.class);
        try (var scenario = new BlockingCommandScenario<>(configured)) {
            scenario.execute(new CreateTask("task-1", "First title"))
                .shouldSucceed()
                .shouldHaveNoResponse();
        }

        var event = ChronicleCommandScenarios.chronicle(configured)
            .shouldHaveAppendedEvent("task-1", TaskCreated.class);
        assertEquals("First title", event.title());
    }

    @Test
    void renameInjectsTheCurrentModelAndConstructsAnExactScope() {
        var current = new TaskView("task-1", "Tenant-local title", 7L);
        var configured = new CommandScenario<>(module, RenameTask.class)
            .addReadModelResolver(new FixedTaskViewResolver(current))
            .withTenant("tenant-a");

        try (var scenario = new BlockingCommandScenario<>(configured)) {
            scenario.execute(new RenameTask("task-1", "Renamed title", 7L))
                .shouldSucceed()
                .shouldHaveNoResponse();
        }

        var event = ChronicleCommandScenarios.chronicle(configured)
            .shouldHaveAppendedEvent("task-1", TaskRenamed.class);
        assertEquals(new TaskRenamed("Tenant-local title", "Renamed title"), event);

        var response = new RenameTask("task-1", "Renamed title", 7L).handle(current).toCompletableFuture().join();
        assertEquals(7L, response.expectedSequenceNumber("task-1"));
    }

    @Test
    void generatedQueriesUseTheCapturedTenantNamespace() {
        var expected = new TaskView("task-1", "Projected title", 2L);
        var reader = new CapturingTaskViewReader(expected);

        var byIdScenario = new QueryScenario<TaskView>(
            module,
            new FullyQualifiedQueryName("io.cratis.arc.samples.javachronicle.TaskView.byId"))
            .addService(TaskViewReader.class, reader)
            .withTenant("tenant-a");
        try (var query = new BlockingQueryScenario<>(byIdScenario)) {
            query.perform(Map.of("id", "task-1"))
                .shouldSucceed()
                .shouldHaveData(expected);
        }

        var allScenario = new QueryScenario<List<TaskView>>(
            module,
            new FullyQualifiedQueryName("io.cratis.arc.samples.javachronicle.TaskView.all"))
            .addService(TaskViewReader.class, reader)
            .withTenant("tenant-a");
        try (var query = new BlockingQueryScenario<>(allScenario)) {
            query.perform()
                .shouldSucceed()
                .shouldHaveData(List.of(expected));
        }

        assertEquals(List.of("tenant-a:task-1", "tenant-a:*"), reader.calls);
    }

    private static final class FixedTaskViewResolver implements BlockingReadModelForCommandResolver {
        private final TaskView value;

        private FixedTaskViewResolver(TaskView value) {
            this.value = value;
        }

        @Override
        public java.util.Set<Class<?>> readModelTypes() {
            return java.util.Set.of(TaskView.class);
        }

        @Override
        public ReadModelForCommandOwnership ownership() {
            return ReadModelForCommandOwnership.DECLARED;
        }

        @Override
        public Object resolveBlocking(
            Class<?> readModelType,
            io.cratis.arc.commands.CommandContext commandContext,
            Object key
        ) {
            return value;
        }
    }

    private static final class CapturingTaskViewReader implements TaskViewReader {
        private final TaskView value;
        private final java.util.ArrayList<String> calls = new java.util.ArrayList<>();

        private CapturingTaskViewReader(TaskView value) {
            this.value = value;
        }

        @Override
        public CompletionStage<TaskView> byId(String namespace, String id) {
            calls.add(namespace + ":" + id);
            return CompletableFuture.completedFuture(value);
        }

        @Override
        public CompletionStage<List<TaskView>> all(String namespace) {
            calls.add(namespace + ":*");
            return CompletableFuture.completedFuture(List.of(value));
        }
    }
}
