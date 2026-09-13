// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.java.AsyncObservableQueryOpenResult;
import io.cratis.arc.java.BlockingQueryPerformer;
import io.cratis.arc.java.BlockingQueryPerformerAdapter;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.metadata.RouteOptions;
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
import io.cratis.arc.queries.DefaultObservableQueryPipeline;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.queries.QueryTransportType;
import io.cratis.arc.results.QueryResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdkPublisherFlowJavaConformanceTest {
    @Test
    void fastJavaPublisherBackpressuresAndCompletesLosslesslyThroughTheJavaFacade() throws Exception {
        FastPublisher upstream = new FastPublisher();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(executor)) {
            RecordingSubscriber subscriber = new RecordingSubscriber();
            open(scope, upstream).subscribe(subscriber);
            assertEquals(0, upstream.subscriptions.get());
            subscriber.subscription.request(1);
            subscriber.first.get(2, TimeUnit.SECONDS);
            // A same-executor barrier proves collection yielded while Java demand is gated; no sleep.
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertEquals(List.of(0), subscriber.values);
            assertFalse(upstream.cancelled.get(), "Slow Java demand must not cancel a compliant publisher");
            assertTrue(upstream.emitted.get() <= 65);

            subscriber.subscription.request(Long.MAX_VALUE);
            subscriber.completed.get(2, TimeUnit.SECONDS);
            assertEquals(IntStream.range(0, 1_000).boxed().toList(), subscriber.values);
            assertEquals(1, upstream.subscriptions.get());
            assertTrue(upstream.completed.get());
            assertTrue(upstream.cancellation.await(2, TimeUnit.SECONDS));
        }
        assertTrue(executor.isShutdown());
    }

    @Test
    void cancellingJavaDemandReleasesUpstreamWithoutAnotherEmission() throws Exception {
        FastPublisher upstream = new FastPublisher();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(executor)) {
            RecordingSubscriber subscriber = new RecordingSubscriber();
            open(scope, upstream).subscribe(subscriber);
            subscriber.subscription.request(1);
            subscriber.first.get(2, TimeUnit.SECONDS);
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertFalse(upstream.cancelled.get());
            int beforeCancellation = upstream.emitted.get();
            executor.submit(subscriber.subscription::cancel).get(2, TimeUnit.SECONDS);
            assertTrue(upstream.cancellation.await(2, TimeUnit.SECONDS));
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertEquals(beforeCancellation, upstream.emitted.get());
            assertEquals(List.of(0), subscriber.values);
            assertFalse(subscriber.completed.isDone(), () -> subscriber.completed.handle(
                (ignored, failure) -> "Unexpected terminal signal: " + failure).join());
        }
    }

    private static Flow.Publisher<QueryResult<?>> open(JavaAsyncScope scope, FastPublisher upstream) throws Exception {
        FullyQualifiedQueryName name = new FullyQualifiedQueryName("Tests.fastPublisher");
        ConcurrentQueryPerformerRegistry performers = new ConcurrentQueryPerformerRegistry();
        performers.register(new BlockingQueryPerformerAdapter(new BlockingQueryPerformer() {
            @Override public QueryDescriptor getDescriptor() {
                return new QueryDescriptor("fastPublisher", "Tests", Integer.class.getName(), List.of(),
                    new RouteOptions(null, QueryTransportType.OBSERVABLE));
            }
            @Override public FullyQualifiedQueryName getFullyQualifiedName() { return name; }
            @Override public Object perform(QueryContext context) { return upstream; }
        }));
        ServiceResolver services = new ServiceResolver() {
            @Override public <T> T resolve(Class<T> type) { return null; }
        };
        QueryExecutionOptions options = new QueryExecutionOptions(
            UUID.randomUUID(), new ArcPrincipal("Ada", true, Set.of()), services);
        AsyncObservableQueryOpenResult opened = scope.observableQueries(new DefaultObservableQueryPipeline(performers))
            .open(new QueryRequest(name), options).toCompletableFuture().get(2, TimeUnit.SECONDS);
        return ((AsyncObservableQueryOpenResult.Stream) opened).getResults();
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<QueryResult<?>> {
        private final List<Object> values = Collections.synchronizedList(new ArrayList<>());
        private final CompletableFuture<Void> first = new CompletableFuture<>();
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private Flow.Subscription subscription;
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; }
        @Override public void onNext(QueryResult<?> value) {
            if (!value.isSuccess()) {
                completed.completeExceptionally(new AssertionError("Unexpected query failure"));
                return;
            }
            values.add(value.getData());
            first.complete(null);
        }
        @Override public void onError(Throwable failure) {
            first.completeExceptionally(failure);
            completed.completeExceptionally(failure);
        }
        @Override public void onComplete() { completed.complete(null); }
    }

    /** Synchronous and reentrant, emitting only requested items and no terminal signal on cancel. */
    private static final class FastPublisher implements Flow.Publisher<Integer> {
        private final AtomicInteger subscriptions = new AtomicInteger();
        private final AtomicInteger emitted = new AtomicInteger();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final CountDownLatch cancellation = new CountDownLatch(1);
        @Override public void subscribe(Flow.Subscriber<? super Integer> subscriber) {
            subscriptions.incrementAndGet();
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) {
                    assertTrue(count > 0);
                    for (long index = 0; index < count && emitted.get() < 1_000 && !cancelled.get(); index++) {
                        subscriber.onNext(emitted.getAndIncrement());
                    }
                    if (emitted.get() == 1_000 && !cancelled.get() && completed.compareAndSet(false, true)) {
                        subscriber.onComplete();
                    }
                }
                @Override public void cancel() {
                    cancelled.set(true);
                    cancellation.countDown();
                }
            });
        }
    }
}
