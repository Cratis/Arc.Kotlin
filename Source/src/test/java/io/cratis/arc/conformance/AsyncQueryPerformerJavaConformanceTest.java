// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.java.AsyncQueryPerformer;
import io.cratis.arc.java.AsyncQueryPerformerAdapter;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
import io.cratis.arc.queries.DefaultQueryPipeline;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.results.QueryResult;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises genuine suspension through the public Java facade, not already-completed stages. */
final class AsyncQueryPerformerJavaConformanceTest {
    @Test
    void incompletePerformerStageResumesWithOneSuccessfulResult() throws Exception {
        try (SuspendedQuery query = new SuspendedQuery()) {
            query.assertSuspended();
            assertTrue(query.performerResult.complete("resumed"));
            QueryResult<?> result = query.execution.get(5, TimeUnit.SECONDS);
            query.assertTerminatedOnce();
            assertEquals(query.correlationId, result.getCorrelationId());
            assertTrue(result.isSuccess());
            assertEquals("resumed", result.getData());
            assertEquals(List.of(), result.getExceptionMessages());
            assertFalse(query.performerResult.isCancelled());
        }
    }

    @Test
    void exceptionalPerformerCompletionResumesWithOneErrorEnvelope() throws Exception {
        try (SuspendedQuery query = new SuspendedQuery()) {
            query.assertSuspended();
            assertTrue(query.performerResult.completeExceptionally(
                new CompletionException(new IllegalStateException("suspended query failed"))));
            QueryResult<?> result = query.execution.get(5, TimeUnit.SECONDS);
            query.assertTerminatedOnce();
            assertEquals(query.correlationId, result.getCorrelationId());
            assertFalse(result.isSuccess());
            assertNull(result.getData());
            assertEquals(List.of("suspended query failed"), result.getExceptionMessages());
            assertTrue(result.getExceptionStackTrace().contains("IllegalStateException: suspended query failed"));
            assertFalse(query.execution.isCompletedExceptionally());
            assertTrue(query.performerResult.isCompletedExceptionally());
        }
    }

    @Test
    void cancellingReturnedStageCancelsTheSuspendedPerformerFuture() throws Exception {
        try (SuspendedQuery query = new SuspendedQuery()) {
            query.assertSuspended();
            assertTrue(query.execution.cancel(false));
            query.assertTerminatedOnce();
            assertThrows(CancellationException.class, () -> query.execution.get(5, TimeUnit.SECONDS));
            assertThrows(CancellationException.class, () -> query.performerResult.get(5, TimeUnit.SECONDS));
            assertTrue(query.execution.isCancelled());
            assertTrue(query.performerResult.isCancelled());
            assertFalse(query.performerResult.complete("too late"));
        }
    }

    private static final class SuspendedQuery implements AutoCloseable {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final JavaAsyncScope scope = JavaAsyncScope.usingExecutor(executor);
        private final UUID correlationId = UUID.randomUUID();
        private final CountDownLatch invoked = new CountDownLatch(1);
        private final CountDownLatch performerTerminal = new CountDownLatch(1);
        private final CountDownLatch executionTerminal = new CountDownLatch(1);
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicInteger performerTerminals = new AtomicInteger();
        private final AtomicInteger executionTerminals = new AtomicInteger();
        private final CompletableFuture<Object> performerResult = new CompletableFuture<>();
        private final CompletableFuture<QueryResult<?>> execution;

        private SuspendedQuery() {
            FullyQualifiedQueryName name = new FullyQualifiedQueryName("Tests.suspended");
            ConcurrentQueryPerformerRegistry performers = new ConcurrentQueryPerformerRegistry();
            performers.register(new AsyncQueryPerformerAdapter(new AsyncQueryPerformer() {
                @Override public QueryDescriptor getDescriptor() {
                    return new QueryDescriptor("suspended", "Tests", String.class.getName());
                }
                @Override public FullyQualifiedQueryName getFullyQualifiedName() { return name; }
                @Override public CompletionStage<?> perform(QueryContext context) {
                    invocations.incrementAndGet();
                    invoked.countDown();
                    return performerResult;
                }
            }));
            ServiceResolver services = new ServiceResolver() {
                @Override public <T> T resolve(Class<T> type) { return null; }
            };
            performerResult.whenComplete((value, failure) -> {
                performerTerminals.incrementAndGet();
                performerTerminal.countDown();
            });
            execution = scope.queries(new DefaultQueryPipeline(performers)).perform(
                new QueryRequest(name), new QueryExecutionOptions(correlationId, new ArcPrincipal(), services,
                    null, null, null, true)).toCompletableFuture();
            execution.whenComplete((value, failure) -> {
                executionTerminals.incrementAndGet();
                executionTerminal.countDown();
            });
        }

        private void assertSuspended() throws Exception {
            assertTrue(invoked.await(5, TimeUnit.SECONDS));
            // The only executor thread has returned from perform and registered await's cancellation handler.
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertFalse(performerResult.isDone());
            assertFalse(execution.isDone());
            assertEquals(1, invocations.get());
            assertEquals(0, performerTerminals.get());
            assertEquals(0, executionTerminals.get());
        }

        private void assertTerminatedOnce() throws Exception {
            assertTrue(performerTerminal.await(5, TimeUnit.SECONDS));
            assertTrue(executionTerminal.await(5, TimeUnit.SECONDS));
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertEquals(1, invocations.get());
            assertEquals(1, performerTerminals.get());
            assertEquals(1, executionTerminals.get());
        }

        @Override public void close() {
            scope.close();
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                }
            } catch (InterruptedException exception) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted during query executor cleanup", exception);
            }
        }
    }
}
