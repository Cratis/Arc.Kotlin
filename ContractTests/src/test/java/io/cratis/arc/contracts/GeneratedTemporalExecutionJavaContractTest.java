// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts;

import io.cratis.arc.artifacts.ArcArtifactModuleRegistry;
import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.CommandExecutionOptions;
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry;
import io.cratis.arc.commands.DefaultCommandPipeline;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.contracts.fixtures.JavaTemporalCommand;
import io.cratis.arc.contracts.fixtures.JavaTemporalReadModel;
import io.cratis.arc.contracts.fixtures.JavaTemporalResult;
import io.cratis.arc.generated.ContractTestsArcArtifactModule;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
import io.cratis.arc.queries.DefaultQueryPipeline;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.results.CommandResult;
import io.cratis.arc.results.QueryResult;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java consumer execution contract for generated handlers and performers with direct JVM value types. */
final class GeneratedTemporalExecutionJavaContractTest {
    private static final ServiceResolver NO_SERVICES = new ServiceResolver() {
        @Override
        public <T> T resolve(Class<T> type) {
            return null;
        }
    };

    @Test
    void javaTemporalArtifactsExecuteThroughThePublicJavaFacade() throws Exception {
        UUID identifier = UUID.fromString("22222222-2222-2222-2222-222222222222");
        LocalDate date = LocalDate.of(2026, 8, 31);
        LocalTime time = LocalTime.of(13, 45, 15);
        ConcurrentCommandHandlerRegistry commandHandlers = new ConcurrentCommandHandlerRegistry();
        ConcurrentQueryPerformerRegistry queryPerformers = new ConcurrentQueryPerformerRegistry();
        ArcArtifactModuleRegistry.register(
            new ContractTestsArcArtifactModule(),
            commandHandlers,
            queryPerformers
        );
        DefaultCommandPipeline commandPipeline = new DefaultCommandPipeline(commandHandlers);
        DefaultQueryPipeline queryPipeline = new DefaultQueryPipeline(queryPerformers);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(executor)) {
                CommandResult<?> commandResult = scope.commands(commandPipeline)
                    .execute(
                        new JavaTemporalCommand(identifier, date, time),
                        new CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), NO_SERVICES)
                    )
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

                // Generated performers expose Arc's suspending SPI; Java consumers invoke them through this public
                // CompletionStage pipeline facade rather than calling generated coroutine methods directly.
                FullyQualifiedQueryName queryName = new FullyQualifiedQueryName(
                    "io.cratis.arc.contracts.fixtures.JavaTemporalReadModel.findJavaTemporal"
                );
                QueryResult<?> queryResult = scope.queries(queryPipeline)
                    .perform(
                        new QueryRequest(
                            queryName,
                            Map.of("identifier", identifier, "date", date, "time", time)
                        ),
                        new QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), NO_SERVICES)
                    )
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

                assertTrue(commandResult.isSuccess());
                assertEquals(new JavaTemporalResult(identifier, date, time), commandResult.getResponse());
                assertTrue(queryResult.isSuccess());
                assertEquals(new JavaTemporalReadModel(identifier, date, time), queryResult.getData());
            }
            assertFalse(executor.isShutdown());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
