// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandExecutionOptions;
import io.cratis.arc.commands.CommandHandlerRegistry;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.java.AsyncCommandHandler;
import io.cratis.arc.java.AsyncCommandHandlerAdapter;
import io.cratis.arc.java.AsyncQueryPerformer;
import io.cratis.arc.java.AsyncQueryPerformerAdapter;
import io.cratis.arc.java.BlockingCommandPipeline;
import io.cratis.arc.java.BlockingQueryPipeline;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryPerformerRegistry;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.results.CommandResult;
import io.cratis.arc.results.QueryResult;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlockingPipelineJavaConformanceTest {
    private static final FullyQualifiedQueryName QUERY = new FullyQualifiedQueryName("Jobs.latest");
    private static final ArcPrincipal IDENTITY = new ArcPrincipal("scheduler", true, Set.of("operator"));

    @Test
    void injectedOrdinaryJavaScheduledServiceUsesDefaultPipelinesWithoutJoinOrContinuation() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArcAutoConfiguration.class))
            .withUserConfiguration(JobConfiguration.class)
            .run(context -> {
                var service = context.getBean(ScheduledService.class);
                var calls = new AtomicInteger();
                var caller = Thread.currentThread();
                context.getBean(CommandHandlerRegistry.class).register(new AsyncCommandHandlerAdapter(new AsyncCommandHandler() {
                    @Override public Class<?> getCommandType() { return Work.class; }
                    @Override public CommandDescriptor getMetadata() { return new CommandDescriptor("Work", Work.class.getName()); }
                    @Override public CompletionStage<?> invoke(CommandContext commandContext) {
                        assertSame(caller, Thread.currentThread());
                        assertSame(IDENTITY, commandContext.getPrincipal());
                        assertEquals("tenant-a", commandContext.getTenantId());
                        assertEquals("namespace-a", commandContext.getTenantNamespace());
                        assertSame(context.getBean(ServiceResolver.class), commandContext.getServiceResolver());
                        calls.incrementAndGet();
                        return CompletableFuture.completedFuture("done");
                    }
                }));
                context.getBean(QueryPerformerRegistry.class).register(new AsyncQueryPerformerAdapter(new AsyncQueryPerformer() {
                    @Override public QueryDescriptor getDescriptor() { return new QueryDescriptor("latest", "Jobs", String.class.getName()); }
                    @Override public FullyQualifiedQueryName getFullyQualifiedName() { return QUERY; }
                    @Override public CompletionStage<?> perform(QueryContext queryContext) {
                        assertSame(caller, Thread.currentThread());
                        assertSame(IDENTITY, queryContext.getPrincipal());
                        assertEquals("tenant-a", queryContext.getTenantId());
                        assertEquals("namespace-a", queryContext.getTenantNamespace());
                        assertSame(context.getBean(ServiceResolver.class), queryContext.getServiceResolver());
                        return CompletableFuture.completedFuture("latest");
                    }
                }));
                // Invoke deterministically, without enabling a background scheduler in the test context.
                service.tick();
                assertEquals(1, calls.get(), "validate must not execute the handler");
                assertTrue(service.validation.isSuccess());
                assertTrue(service.command.isSuccess());
                assertTrue(service.query.isSuccess());
                assertEquals("done", service.command.getResponse());
                assertEquals("latest", service.query.getData());
                assertEquals(service.command.getCorrelationId(), service.query.getCorrelationId());
            });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ScheduledService.class)
    static class JobConfiguration {
        @Bean ArcPrincipal jobPrincipal() { return IDENTITY; }
    }

    @Service
    static final class ScheduledService {
        private final BlockingCommandPipeline commands;
        private final BlockingQueryPipeline queries;
        private final ServiceResolver services;
        private final ArcPrincipal identity;
        private CommandResult<?> validation;
        private CommandResult<?> command;
        private QueryResult<?> query;

        ScheduledService(BlockingCommandPipeline commands, BlockingQueryPipeline queries,
                         ServiceResolver services, ArcPrincipal identity) {
            this.commands = commands;
            this.queries = queries;
            this.services = services;
            this.identity = identity;
        }

        @Scheduled(fixedDelay = 60000)
        public void tick() {
            var correlation = UUID.randomUUID();
            var commandOptions = new CommandExecutionOptions(correlation, identity, services, "tenant-a", "namespace-a");
            var queryOptions = new QueryExecutionOptions(correlation, identity, services, "tenant-a", "namespace-a");
            validation = commands.validate(new Work(), commandOptions);
            command = commands.execute(new Work(), commandOptions);
            query = queries.perform(new QueryRequest(QUERY), queryOptions);
        }
    }

    private record Work() { }
}
