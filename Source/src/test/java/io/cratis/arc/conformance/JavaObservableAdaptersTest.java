// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.java.AsyncObservableQueryOpenResult;
import io.cratis.arc.java.AsyncObservableQueryPipeline;
import io.cratis.arc.java.AsyncQueryPerformer;
import io.cratis.arc.java.AsyncQueryPerformerAdapter;
import io.cratis.arc.java.BlockingQueryPerformer;
import io.cratis.arc.java.BlockingQueryPerformerAdapter;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.metadata.RouteOptions;
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
import io.cratis.arc.queries.DefaultObservableQueryPipeline;
import io.cratis.arc.queries.DefaultQueryHealthTracker;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.ObservableQueryTransferMode;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.queries.QuerySubscriptionClientInfo;
import io.cratis.arc.queries.QuerySubscriptionMetadata;
import io.cratis.arc.queries.QueryTransportType;
import io.cratis.arc.results.QueryResult;
import java.time.Instant;
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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JavaObservableAdaptersTest {
    private static final FullyQualifiedQueryName QUERY_NAME = new FullyQualifiedQueryName("Tests.observe");
    private static final ServiceResolver NO_SERVICES = new ServiceResolver() {
        @Override public <T> T resolve(Class<T> type) { return null; }
    };

    @Test
    void observableResultsHonorDemandAndCancellation() throws Exception {
        RecordingPublisher<List<String>> upstream = new RecordingPublisher<>();
        ConcurrentQueryPerformerRegistry performers = new ConcurrentQueryPerformerRegistry();
        performers.register(new BlockingQueryPerformerAdapter(new BlockingQueryPerformer() {
            @Override public QueryDescriptor getDescriptor() { return observableDescriptor(); }
            @Override public FullyQualifiedQueryName getFullyQualifiedName() { return QUERY_NAME; }
            @Override public Object perform(io.cratis.arc.queries.QueryContext context) { return upstream; }
        }));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(executor)) {
            AsyncObservableQueryPipeline pipeline = scope.observableQueries(new DefaultObservableQueryPipeline(performers));
            AsyncObservableQueryOpenResult opened = pipeline.open(
                new QueryRequest(QUERY_NAME), options(), ObservableQueryTransferMode.FULL, value -> value)
                .toCompletableFuture().join();
            AsyncObservableQueryOpenResult.Stream stream = (AsyncObservableQueryOpenResult.Stream) opened;
            RecordingSubscriber<QueryResult<?>> subscriber = new RecordingSubscriber<>();
            stream.getResults().subscribe(subscriber);

            assertFalse(upstream.subscribed.get());
            assertTrue(subscriber.values.isEmpty());

            subscriber.subscription.request(1);
            assertTrue(upstream.subscribedLatch.await(2, TimeUnit.SECONDS));
            upstream.emit(List.of("one"));
            assertTrue(subscriber.firstValue.await(2, TimeUnit.SECONDS));
            upstream.emit(List.of("two"));
            assertEquals(1, subscriber.values.size());
            assertEquals(List.of("one"), subscriber.values.get(0).getData());

            subscriber.subscription.cancel();
            assertTrue(upstream.cancelledLatch.await(2, TimeUnit.SECONDS));
        }
        assertTrue(executor.isShutdown());
    }

    @Test
    void cancellingOpenCancelsTheJavaCompletionStage() throws Exception {
        CountDownLatch performerStarted = new CountDownLatch(1);
        CountDownLatch performerCancelled = new CountDownLatch(1);
        CompletableFuture<Object> performerResult = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                performerCancelled.countDown();
                return super.cancel(mayInterruptIfRunning);
            }
        };
        ConcurrentQueryPerformerRegistry performers = new ConcurrentQueryPerformerRegistry();
        performers.register(new AsyncQueryPerformerAdapter(new AsyncQueryPerformer() {
            @Override public QueryDescriptor getDescriptor() { return observableDescriptor(); }
            @Override public FullyQualifiedQueryName getFullyQualifiedName() { return QUERY_NAME; }
            @Override public java.util.concurrent.CompletionStage<?> perform(io.cratis.arc.queries.QueryContext context) {
                performerStarted.countDown();
                return performerResult;
            }
        }));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(executor)) {
            AsyncObservableQueryPipeline pipeline = scope.observableQueries(new DefaultObservableQueryPipeline(performers));
            CompletableFuture<AsyncObservableQueryOpenResult> opening = pipeline.open(new QueryRequest(QUERY_NAME), options())
                .toCompletableFuture();
            assertTrue(performerStarted.await(2, TimeUnit.SECONDS));
            opening.cancel(true);
            assertTrue(performerCancelled.await(2, TimeUnit.SECONDS));
            assertTrue(opening.isCancelled());
            assertTrue(performerResult.isCancelled());
        }
    }

    @Test
    void queryHealthPublisherIsDemandAware() throws Exception {
        DefaultQueryHealthTracker tracker = new DefaultQueryHealthTracker();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(executor)) {
            RecordingSubscriber<io.cratis.arc.queries.QueryHealth> subscriber = new RecordingSubscriber<>();
            scope.queryHealth(tracker).subscribe(subscriber);
            tracker.registerSubscription("connection", "sse", new QuerySubscriptionMetadata(
                "subscription", QUERY_NAME.value(), String.class.getName(), Instant.now(),
                new QuerySubscriptionClientInfo(null, null, "user", "sse")));
            assertTrue(subscriber.values.isEmpty());

            subscriber.subscription.request(1);
            assertTrue(subscriber.firstValue.await(2, TimeUnit.SECONDS));
            assertEquals(1, subscriber.values.get(0).getTotalSubscriptions());
            subscriber.subscription.cancel();
        } finally {
            executor.shutdownNow();
        }
    }

    private static QueryDescriptor observableDescriptor() {
        return new QueryDescriptor(
            "observe", "Tests", List.class.getName(), List.of(), new RouteOptions(null, QueryTransportType.OBSERVABLE));
    }

    private static QueryExecutionOptions options() {
        return new QueryExecutionOptions(
            UUID.randomUUID(), new ArcPrincipal("Ada", true, Set.of("operator")), NO_SERVICES);
    }

    private static final class RecordingSubscriber<T> implements Flow.Subscriber<T> {
        private final List<T> values = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch firstValue = new CountDownLatch(1);
        private Flow.Subscription subscription;

        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; }
        @Override public void onNext(T value) { values.add(value); firstValue.countDown(); }
        @Override public void onError(Throwable throwable) { throw new AssertionError(throwable); }
        @Override public void onComplete() { }
    }

    private static final class RecordingPublisher<T> implements Flow.Publisher<T> {
        private final AtomicBoolean subscribed = new AtomicBoolean();
        private final AtomicLong demand = new AtomicLong();
        private final CountDownLatch subscribedLatch = new CountDownLatch(1);
        private final CountDownLatch cancelledLatch = new CountDownLatch(1);
        private Flow.Subscriber<? super T> subscriber;

        @Override public void subscribe(Flow.Subscriber<? super T> value) {
            subscriber = value;
            subscribed.set(true);
            value.onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) { demand.addAndGet(count); }
                @Override public void cancel() { cancelledLatch.countDown(); }
            });
            subscribedLatch.countDown();
        }

        void emit(T value) {
            if (demand.getAndDecrement() <= 0) {
                demand.incrementAndGet();
                throw new AssertionError("Upstream emitted without demand");
            }
            subscriber.onNext(value);
        }
    }
}
