// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kotlinx.coroutines.flow.FlowKt;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Java conformance tests for {@link MongoObservations} static helpers.
 * Proves that the Criteria-based helpers reach the same underlying
 * snapshot behavior as the corresponding direct calls on
 * {@link MongoObservableQuery} and that the Kotlin and Java paths agree.
 */
final class JavaMongoObservationExtensionTests {

    @Test
    void criteriaObservePublisherWaitsForDemandAndFiltersCorrectly() throws Exception {
        MongoOperations operations = mock(MongoOperations.class);
        when(operations.find(any(), eq(MongoTaskReadModel.class)))
            .thenReturn(List.of(new MongoTaskReadModel("one", "First")));
        MongoChangeStreamWatcher watcher = (documentType, tenantId, documentKey) -> FlowKt.emptyFlow();
        MongoObservableQuery queries = new MongoObservableQuery(tenantId -> operations, watcher);
        Criteria criteria = Criteria.where("title").is("First");

        ListSubscriber<MongoTaskReadModel> subscriber = new ListSubscriber<>();
        MongoObservations.observe(queries, MongoTaskReadModel.class, criteria).subscribe(subscriber);

        assertFalse(subscriber.received.await(100, TimeUnit.MILLISECONDS), "Publisher must not emit before demand");
        subscriber.subscription.get().request(1);
        assertTrue(subscriber.received.await(2, TimeUnit.SECONDS), "Publisher must emit after demand");
        assertTrue(subscriber.completed.await(2, TimeUnit.SECONDS));
        assertEquals("one", subscriber.values.get(0).get(0).getId());
        assertNull(subscriber.failure.get());
        queries.close();
    }

    @Test
    void criteriaObserveListPublisherWaitsForDemandAndFiltersCorrectly() throws Exception {
        MongoOperations operations = mock(MongoOperations.class);
        when(operations.find(any(), eq(MongoTaskReadModel.class)))
            .thenReturn(List.of(new MongoTaskReadModel("two", "Second")));
        MongoChangeStreamWatcher watcher = (documentType, tenantId, documentKey) -> FlowKt.emptyFlow();
        MongoObservableQuery queries = new MongoObservableQuery(tenantId -> operations, watcher);
        Criteria criteria = Criteria.where("title").is("Second");

        ListSubscriber<MongoTaskReadModel> subscriber = new ListSubscriber<>();
        MongoObservations.observeList(queries, MongoTaskReadModel.class, criteria).subscribe(subscriber);

        assertFalse(subscriber.received.await(100, TimeUnit.MILLISECONDS), "Publisher must not emit before demand");
        subscriber.subscription.get().request(1);
        assertTrue(subscriber.received.await(2, TimeUnit.SECONDS));
        assertEquals("two", subscriber.values.get(0).get(0).getId());
        assertNull(subscriber.failure.get());
        queries.close();
    }

    @Test
    void criteriaObserveSinglePublisherWaitsForDemandAndEmitsFilteredDocument() throws Exception {
        MongoOperations operations = mock(MongoOperations.class);
        when(operations.find(any(), eq(MongoTaskReadModel.class)))
            .thenReturn(List.of(new MongoTaskReadModel("three", "Third")));
        MongoChangeStreamWatcher watcher = (documentType, tenantId, documentKey) -> FlowKt.emptyFlow();
        MongoObservableQuery queries = new MongoObservableQuery(tenantId -> operations, watcher);
        Criteria criteria = Criteria.where("_id").is("three");

        SingleSubscriber<MongoTaskReadModel> subscriber = new SingleSubscriber<>();
        MongoObservations.observeSingle(queries, MongoTaskReadModel.class, criteria).subscribe(subscriber);

        assertFalse(subscriber.received.await(100, TimeUnit.MILLISECONDS), "Publisher must not emit before demand");
        subscriber.subscription.get().request(1);
        assertTrue(subscriber.received.await(2, TimeUnit.SECONDS));
        assertEquals("three", subscriber.values.get(0).getId());
        assertNull(subscriber.failure.get());
        queries.close();
    }

    @Test
    void criteriaObservePublisherWithTenantPropagatesTenantCorrectly() throws Exception {
        AtomicReference<String> capturedTenant = new AtomicReference<>();
        MongoOperations operations = mock(MongoOperations.class);
        when(operations.find(any(), eq(MongoTaskReadModel.class)))
            .thenReturn(List.of(new MongoTaskReadModel("four", "Fourth")));
        MongoChangeStreamWatcher watcher = (documentType, tenantId, documentKey) -> FlowKt.emptyFlow();
        MongoObservableQuery queries = new MongoObservableQuery(tenantId -> {
            capturedTenant.set(tenantId);
            return operations;
        }, watcher);
        Criteria criteria = Criteria.where("_id").is("four");

        ListSubscriber<MongoTaskReadModel> subscriber = new ListSubscriber<>();
        MongoObservations.observe(queries, MongoTaskReadModel.class, criteria, "tenant-x").subscribe(subscriber);
        subscriber.subscription.get().request(1);
        assertTrue(subscriber.received.await(2, TimeUnit.SECONDS));

        assertEquals("tenant-x", capturedTenant.get());
        queries.close();
    }

    /** Subscriber for {@code Publisher<List<T>>} — used by observe and observeList. */
    private static final class ListSubscriber<T> implements Flow.Subscriber<List<T>> {
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final CopyOnWriteArrayList<List<T>> values = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch received = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void onSubscribe(Flow.Subscription value) { subscription.set(value); }

        @Override
        public void onNext(List<T> value) { values.add(value); received.countDown(); }

        @Override
        public void onError(Throwable throwable) { failure.set(throwable); completed.countDown(); }

        @Override
        public void onComplete() { completed.countDown(); }
    }

    /** Subscriber for {@code Publisher<T>} — used by observeSingle. */
    private static final class SingleSubscriber<T> implements Flow.Subscriber<T> {
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final CopyOnWriteArrayList<T> values = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch received = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void onSubscribe(Flow.Subscription value) { subscription.set(value); }

        @Override
        public void onNext(T value) { values.add(value); received.countDown(); }

        @Override
        public void onError(Throwable throwable) { failure.set(throwable); completed.countDown(); }

        @Override
        public void onComplete() { completed.countDown(); }
    }
}
