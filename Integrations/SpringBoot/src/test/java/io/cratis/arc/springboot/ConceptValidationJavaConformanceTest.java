// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.AsyncCommandPipeline;
import io.cratis.arc.commands.CommandExecutionOptions;
import io.cratis.arc.commands.CommandHandlerRegistry;
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry;
import io.cratis.arc.commands.DefaultCommandPipeline;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.java.AsyncObservableQueryOpenResult;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.queries.AsyncQueryPipeline;
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
import io.cratis.arc.queries.DefaultQueryPipeline;
import io.cratis.arc.queries.ObservableQueryPipeline;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryPerformerRegistry;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.queries.QueryTransportType;
import io.cratis.arc.results.ValidationResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConceptValidationJavaConformanceTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration.class))
        .withUserConfiguration(ConceptValidationJavaFixture.Contributions.class);

    @Test
    void javaRuleBeanRejectsRecordCommandExecutionAndValidationWithoutReplacementFilters() {
        runner.run(context -> {
            var handler = new ArcConceptValidationTests.Handler(ConceptValidationJavaFixture.Command.class);
            context.getBean(CommandHandlerRegistry.class).register(handler);
            var pipeline = context.getBean(AsyncCommandPipeline.class);
            var options = new CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), context.getBean(ServiceResolver.class));
            var execution = pipeline.execute(invalid(), options).toCompletableFuture().get(5, TimeUnit.SECONDS);
            var validation = pipeline.validate(invalid(), options).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertFalse(execution.isSuccess());
            assertFalse(validation.isSuccess());
            assertEquals(List.of("code", "items[0]", "named.first"), members(execution.getValidationResults()));
            assertEquals(execution.getValidationResults().stream().map(ValidationResult::getMessage).toList(),
                List.of("Java code is required", "Java code is required", "Java code is required"));
            assertEquals(members(execution.getValidationResults()), members(validation.getValidationResults()));
            assertEquals(0, handler.getInvocations().get());
            assertTrue(pipeline.execute(valid(), options).toCompletableFuture().get(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(1, handler.getInvocations().get());
        });
    }

    @Test
    void javaRuleBeanRejectsOneShotQueryArgumentRecordsBeforePerformer() {
        runner.run(context -> {
            var performer = new ArcConceptValidationTests.Performer(QueryTransportType.REQUEST_RESPONSE);
            context.getBean(QueryPerformerRegistry.class).register(performer);
            var options = new QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), context.getBean(ServiceResolver.class));
            var pipeline = context.getBean(AsyncQueryPipeline.class);
            var result = pipeline.perform(new QueryRequest(performer.getFullyQualifiedName(), Map.of("input", invalid())), options)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertFalse(result.isSuccess());
            assertEquals(List.of("input.code", "input.items[0]", "input.named.first"), members(result.getValidationResults()));
            assertEquals(0, performer.getInvocations().get());
            assertTrue(pipeline.perform(new QueryRequest(performer.getFullyQualifiedName(), Map.of("input", valid())), options)
                .toCompletableFuture().get(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(1, performer.getInvocations().get());
        });
    }

    @Test
    void javaRuleBeanRejectsObservableBeforeOpeningThroughJavaFacade() {
        runner.run(context -> {
            var performer = new ArcConceptValidationTests.Performer(QueryTransportType.OBSERVABLE);
            context.getBean(QueryPerformerRegistry.class).register(performer);
            var options = new QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), context.getBean(ServiceResolver.class));
            try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(Runnable::run)) {
                var opened = scope.observableQueries(context.getBean(ObservableQueryPipeline.class))
                    .open(new QueryRequest(performer.getFullyQualifiedName(), Map.of("input", invalid())), options)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
                var failure = assertInstanceOf(AsyncObservableQueryOpenResult.Failure.class, opened);
                assertEquals(List.of("input.code", "input.items[0]", "input.named.first"), members(failure.getResult().getValidationResults()));
                assertEquals(0, performer.getInvocations().get());
            }
        });
    }

    @Test
    void originalFactoriesFromJavaUseConceptBeansOnManagedConfigurationOnly() {
        runner.withBean(ArcConceptValidationTests.TypedProviders.class).run(context -> {
            var providers = context.getBean(ArcConceptValidationTests.TypedProviders.class);
            var services = context.getBean(ServiceResolver.class);
            var commandOptions = new CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), services);
            var queryOptions = new QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), services);
            for (boolean managed : List.of(true, false)) {
                var configuration = managed ? context.getBean(ArcAutoConfiguration.class) : new ArcAutoConfiguration();
                var commands = new ConcurrentCommandHandlerRegistry();
                var handler = new ArcConceptValidationTests.Handler(ConceptValidationJavaFixture.Command.class);
                commands.register(handler);
                var queries = new ConcurrentQueryPerformerRegistry();
                var performer = new ArcConceptValidationTests.Performer(QueryTransportType.REQUEST_RESPONSE);
                queries.register(performer);
                // These calls compile against the original one-provider descriptors, without casts or continuations.
                var commandFilter = configuration.arcCommandValidationFilter(providers.getCommands());
                var queryFilter = configuration.arcQueryValidationFilter(providers.getQueries());
                try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(Runnable::run)) {
                    var command = scope.commands(new DefaultCommandPipeline(commands, List.of(commandFilter)))
                        .execute(invalid(), commandOptions).toCompletableFuture().get(5, TimeUnit.SECONDS);
                    var query = scope.queries(new DefaultQueryPipeline(queries, List.of(queryFilter)))
                        .perform(new QueryRequest(performer.getFullyQualifiedName(), Map.of("input", invalid())), queryOptions)
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                    assertEquals(!managed, command.isSuccess());
                    assertEquals(!managed, query.isSuccess());
                    assertEquals(managed ? 3 : 0, command.getValidationResults().size());
                    assertEquals(managed ? 3 : 0, query.getValidationResults().size());
                    assertEquals(managed ? 0 : 1, handler.getInvocations().get());
                    assertEquals(managed ? 0 : 1, performer.getInvocations().get());
                }
            }
        });
    }

    private static List<String> members(List<ValidationResult> results) {
        return results.stream().flatMap(result -> result.getMembers().stream()).toList();
    }

    private static ConceptValidationJavaFixture.Command invalid() {
        return new ConceptValidationJavaFixture.Command(new ConceptValidationJavaFixture.Code(""),
            List.of(new ConceptValidationJavaFixture.Code("")), Map.of("first", new ConceptValidationJavaFixture.Code("")));
    }

    private static ConceptValidationJavaFixture.Command valid() {
        return new ConceptValidationJavaFixture.Command(new ConceptValidationJavaFixture.Code("valid"), List.of(), Map.of());
    }
}
