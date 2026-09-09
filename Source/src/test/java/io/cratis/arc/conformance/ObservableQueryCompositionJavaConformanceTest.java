// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.java.AsyncObservableQueryOpenResult;
import io.cratis.arc.java.BlockingQueryFilterAdapter;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.queries.BlockingObservableQueryEmissionGuard;
import io.cratis.arc.queries.BlockingQueryRendererFor;
import io.cratis.arc.queries.BlockingReadModelInterceptor;
import io.cratis.arc.queries.ChangeSetComputer;
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
import io.cratis.arc.queries.DefaultObservableQueryEmissionGuards;
import io.cratis.arc.queries.DefaultObservableQueryPipeline;
import io.cratis.arc.queries.DefaultQueryRenderers;
import io.cratis.arc.queries.DefaultReadModelInterceptors;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.ObservableQueryTransferMode;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryRendererResult;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.results.QueryResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static io.cratis.arc.queries.ObservableQueryEmissionVerdict.ALLOW;
import static io.cratis.arc.queries.ObservableQueryEmissionVerdict.SUPPRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ObservableQueryCompositionJavaConformanceTest {
    @Test
    void explicitNullPreservesFirstDeliveryAndInterceptedBaselineThroughSuppressedSnapshotAndStream() throws Exception {
        suppressed(null, false);
    }

    @Test
    void explicitDeltaRetainsFirstFullDeliveryAndComputesChangesFromLastDeliveredValue() throws Exception {
        suppressed(ObservableQueryTransferMode.DELTA, false);
    }

    @Test
    void explicitFullNeverComputesChangesThroughSuppression() throws Exception {
        suppressed(ObservableQueryTransferMode.FULL, false);
    }

    @Test
    void javaTwoArgumentOverloadMeansFullNotOmittedSubscriberModeThroughSuppression() throws Exception {
        suppressed(ObservableQueryTransferMode.FULL, true);
    }

    @Test
    void explicitNullSnapshotCompletesAndStreamStartsWithItsOwnAddedBaseline() throws Exception {
        independent(null, false);
    }

    @Test
    void explicitDeltaSnapshotAndStreamHaveIndependentFirstFullDeliveries() throws Exception {
        independent(ObservableQueryTransferMode.DELTA, false);
    }

    @Test
    void explicitFullSnapshotAndStreamEachAnnounceFirstDelivery() throws Exception {
        independent(ObservableQueryTransferMode.FULL, false);
    }

    @Test
    void javaTwoArgumentOverloadMeansFullForSnapshotAndSubsequentStreamValues() throws Exception {
        independent(ObservableQueryTransferMode.FULL, true);
    }

    private static void suppressed(ObservableQueryTransferMode mode, boolean shortCall) throws Exception {
        Fixture fixture = new Fixture("S", Set.of("S", "B"));
        var executor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(executor)) {
            var opened = fixture.open(scope, mode, shortCall);
            try (Probe snapshot = new Probe(fixture.events, "snapshot");
                 Probe results = new Probe(fixture.events, "results")) {
                assertNotNull(opened.getSnapshot());
                opened.getSnapshot().subscribe(snapshot);
                snapshot.request(1);
                snapshot.completed.get(5, TimeUnit.SECONDS);
                fixture.processed("S", true);
                fixture.event("snapshot:complete");
                snapshot.assertTerminal(0);

                opened.getResults().subscribe(results);
                results.request(2);
                fixture.processed("S", true);
                fixture.source.update(rows("A"));
                fixture.processed("A", true);
                fixture.assertEnvelope(results.next(), "A", null, mode);
                fixture.event("results:next");
                fixture.source.update(rows("B"));
                fixture.processed("B", false);
                fixture.source.update(rows("C"));
                fixture.processed("C", false);
                fixture.assertEnvelope(results.next(), "C", "A", mode);
                fixture.event("results:next");
                assertEquals(List.of(true, true, true, false, false), fixture.flags);
                results.assertOpen(2);
                assertTrue(fixture.events.isEmpty());
            }
        } finally {
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "owned scope must terminate its executor");
        }
    }

    private static void independent(ObservableQueryTransferMode mode, boolean shortCall) throws Exception {
        Fixture fixture = new Fixture("A", Set.of());
        var executor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(executor)) {
            var opened = fixture.open(scope, mode, shortCall);
            try (Probe snapshot = new Probe(fixture.events, "snapshot");
                 Probe results = new Probe(fixture.events, "results")) {
                assertNotNull(opened.getSnapshot());
                opened.getSnapshot().subscribe(snapshot);
                snapshot.request(1);
                // Await normal terminal completion, never cancel after the first onNext.
                snapshot.completed.get(5, TimeUnit.SECONDS);
                fixture.processed("A", true);
                fixture.assertEnvelope(snapshot.next(), "A", null, mode);
                fixture.event("snapshot:next");
                fixture.event("snapshot:complete");
                snapshot.assertTerminal(1);

                fixture.source.update(rows("B")); // Deliberately before results collection begins.
                opened.getResults().subscribe(results);
                results.request(2);
                fixture.processed("B", true);
                fixture.assertEnvelope(results.next(), "B", null, mode);
                fixture.event("results:next");
                fixture.source.update(rows("C"));
                fixture.processed("C", false);
                fixture.assertEnvelope(results.next(), "C", "B", mode);
                fixture.event("results:next");
                assertEquals(List.of(true, true, false), fixture.flags);
                results.assertOpen(2);
                assertTrue(fixture.events.isEmpty());
            }
        } finally {
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "owned scope must terminate its executor");
        }
    }

    private static final class Fixture {
        private final FullyQualifiedQueryName name = new FullyQualifiedQueryName("JavaComposition.observe");
        private final ArcPrincipal principal = new ArcPrincipal("Ada", true, Set.of("operator"));
        private final ServiceResolver services = new ServiceResolver() {
            @Override public <T> T resolve(Class<T> type) { return null; }
        };
        private final QueryExecutionOptions options = new QueryExecutionOptions(
            UUID.randomUUID(), principal, services, "tenant", "namespace");
        private final QueryRequest request;
        private final ObservableCompositionSource source;
        private final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        private final List<Boolean> flags = Collections.synchronizedList(new ArrayList<>());
        private final DefaultObservableQueryPipeline pipeline;

        Fixture(String initial, Set<String> suppressed) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("argument", "captured");
            arguments.put("explicitNull", null);
            request = new QueryRequest(name, arguments);
            source = new ObservableCompositionSource(rows(initial), name);
            var registry = new ConcurrentQueryPerformerRegistry();
            registry.register(source.performer());
            var renderer = new BlockingQueryRendererFor<Object>() {
                @Override public Class<Object> queryType() { return Object.class; }
                @Override public QueryRendererResult renderBlocking(
                    Object query, QueryRendererResult current, QueryContext context) {
                    assertContext(context);
                    var values = items(query);
                    assertEquals(rows(values.get(0).value()), values);
                    events.add("render:" + values.get(0).value());
                    return current;
                }
            };
            var interceptor = new BlockingReadModelInterceptor<Item>() {
                @Override public Class<Item> readModelType() { return Item.class; }
                @Override public Item interceptBlocking(Item item, QueryContext context) {
                    assertContext(context);
                    events.add("intercept:" + item.value() + ":" + item.key());
                    return new Item(item.key(), item.value() + "!");
                }
            };
            BlockingObservableQueryEmissionGuard guard = context -> {
                assertEquals(name, context.getQueryName());
                assertEquals(request.getArguments(), context.getArguments());
                assertSame(principal, context.getPrincipal());
                assertSame(services, context.getServiceResolver());
                assertEquals(options.getCorrelationId(), context.getCorrelationId());
                assertEquals("tenant", context.getTenantId());
                assertEquals("namespace", context.getTenantNamespace());
                var values = items(context.getData());
                String step = values.get(0).value().replace("!", "");
                assertEquals(intercepted(step), values);
                flags.add(context.isFirstEmission());
                events.add("guard:" + step + ":" + context.isFirstEmission());
                return suppressed.contains(step) ? SUPPRESS : ALLOW;
            };
            pipeline = new DefaultObservableQueryPipeline(
                registry,
                List.of(new BlockingQueryFilterAdapter(context -> {
                    assertContext(context);
                    return QueryResult.success(context.getCorrelationId());
                })),
                new ChangeSetComputer(), new DefaultQueryRenderers(List.of(renderer)),
                new DefaultReadModelInterceptors(List.of(interceptor)),
                new DefaultObservableQueryEmissionGuards(List.of(guard)));
        }

        AsyncObservableQueryOpenResult.Stream open(
            JavaAsyncScope scope, ObservableQueryTransferMode mode, boolean shortCall) throws Exception {
            var facade = scope.observableQueries(pipeline);
            var opening = shortCall ? facade.open(request, options)
                : facade.open(request, options, mode, value -> Item.class.cast(value).key());
            var opened = opening.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(opened instanceof AsyncObservableQueryOpenResult.Stream);
            return (AsyncObservableQueryOpenResult.Stream) opened;
        }

        void assertContext(QueryContext context) {
            assertSame(request, context.getRequest());
            assertEquals(request.getArguments(), context.getRequest().getArguments());
            assertEquals(name, context.getQueryName());
            assertSame(principal, context.getPrincipal());
            assertSame(services, context.getServiceResolver());
            assertEquals(options.getCorrelationId(), context.getCorrelationId());
            assertEquals("tenant", context.getTenantId());
            assertEquals("namespace", context.getTenantNamespace());
        }

        void event(String expected) throws Exception {
            assertEquals(expected, events.poll(5, TimeUnit.SECONDS), "named processing acknowledgement");
        }

        void processed(String step, boolean first) throws Exception {
            event("render:" + step);
            for (Item item : rows(step)) event("intercept:" + step + ":" + item.key());
            event("guard:" + step + ":" + first);
        }

        void assertEnvelope(QueryResult<?> result, String step, String previous, ObservableQueryTransferMode mode) {
            assertTrue(result.isSuccess());
            assertTrue(result.isReady());
            assertEquals(options.getCorrelationId(), result.getCorrelationId());
            boolean delta = mode == ObservableQueryTransferMode.DELTA && previous != null;
            assertEquals(delta ? null : intercepted(step), result.getData());
            if (mode == ObservableQueryTransferMode.FULL || (mode == ObservableQueryTransferMode.DELTA && previous == null)) {
                assertNull(result.getChangeSet());
            } else {
                var changes = result.getChangeSet();
                assertNotNull(changes);
                if (previous == null) {
                    assertEquals(intercepted(step), changes.getAdded(), "each collection starts without a baseline");
                    assertEquals(List.of(), changes.getReplaced());
                    assertEquals(List.of(), changes.getRemoved());
                } else {
                    assertEquals(List.of(new Item(4, "C!")), changes.getAdded());
                    assertEquals(List.of(new Item(1, "C!")), changes.getReplaced());
                    assertEquals(List.of(new Item(previous.equals("A") ? 2 : 3, previous + "!")), changes.getRemoved());
                }
            }
        }
    }

    private static final class Probe implements Flow.Subscriber<QueryResult<?>>, AutoCloseable {
        private final LinkedBlockingQueue<QueryResult<?>> values = new LinkedBlockingQueue<>();
        private final LinkedBlockingQueue<String> events;
        private final String name;
        private final CompletableFuture<Flow.Subscription> subscription = new CompletableFuture<>();
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final AtomicInteger nextCount = new AtomicInteger();
        private final AtomicInteger completeCount = new AtomicInteger();

        Probe(LinkedBlockingQueue<String> events, String name) {
            this.events = events;
            this.name = name;
        }

        @Override public void onSubscribe(Flow.Subscription value) { subscription.complete(value); }
        @Override public void onNext(QueryResult<?> value) {
            nextCount.incrementAndGet();
            events.add(name + ":next");
            values.add(value);
        }
        @Override public void onError(Throwable failure) {
            error.set(failure);
            completed.completeExceptionally(failure);
        }
        @Override public void onComplete() {
            completeCount.incrementAndGet();
            events.add(name + ":complete");
            completed.complete(null);
        }

        void request(long count) throws Exception {
            assertTrue(count > 0);
            subscription.get(5, TimeUnit.SECONDS).request(count);
        }

        QueryResult<?> next() throws Exception {
            var result = values.poll(5, TimeUnit.SECONDS);
            assertNull(error.get());
            assertNotNull(result, "expected an acknowledged delivery");
            return result;
        }

        void assertTerminal(int expectedValues) {
            assertNull(error.get());
            assertEquals(expectedValues, nextCount.get());
            assertEquals(1, completeCount.get());
            assertTrue(values.isEmpty());
        }

        void assertOpen(int expectedValues) {
            assertNull(error.get());
            assertEquals(expectedValues, nextCount.get());
            assertEquals(0, completeCount.get());
            assertTrue(values.isEmpty());
        }

        @Override public void close() {
            var value = subscription.getNow(null);
            if (value != null) value.cancel();
        }
    }

    private record Item(int key, String value) { }

    private static List<Item> items(Object value) {
        assertTrue(value instanceof List<?>);
        return ((List<?>) value).stream().map(Item.class::cast).toList();
    }

    private static List<Item> rows(String step) {
        return switch (step) {
            case "S" -> List.of(new Item(9, "S"));
            case "A" -> List.of(new Item(1, "A"), new Item(2, "A"));
            case "B" -> List.of(new Item(1, "B"), new Item(3, "B"));
            case "C" -> List.of(new Item(1, "C"), new Item(4, "C"));
            default -> throw new IllegalArgumentException("Unknown step " + step);
        };
    }

    private static List<Item> intercepted(String step) {
        return rows(step).stream().map(item -> new Item(item.key(), item.value() + "!")).toList();
    }
}
