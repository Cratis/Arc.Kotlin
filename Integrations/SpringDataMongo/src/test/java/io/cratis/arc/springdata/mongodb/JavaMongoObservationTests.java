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
import org.springframework.data.mongodb.core.query.Query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JavaMongoObservationTests {
    @Test
    void publisherWaitsForJavaSubscriberDemand() throws Exception {
        MongoOperations operations = mock(MongoOperations.class);
        when(operations.find(any(Query.class), eq(MongoTaskReadModel.class)))
            .thenReturn(List.of(new MongoTaskReadModel("one", "Observed")));
        MongoChangeStreamWatcher watcher = (documentType, tenantId, documentKey) -> FlowKt.emptyFlow();
        MongoObservableQuery queries = new MongoObservableQuery(tenantId -> operations, watcher);
        RecordingSubscriber<MongoTaskReadModel> subscriber = new RecordingSubscriber<>();

        JavaMongoApiAccess.observePublisher(queries).subscribe(subscriber);

        assertFalse(subscriber.received.await(100, TimeUnit.MILLISECONDS));
        subscriber.subscription.get().request(1);
        assertTrue(subscriber.received.await(2, TimeUnit.SECONDS));
        assertTrue(subscriber.completed.await(2, TimeUnit.SECONDS));
        assertEquals("one", subscriber.values.get(0).get(0).getId());
        assertNull(subscriber.failure.get());
        queries.close();
    }

    private static final class RecordingSubscriber<T> implements Flow.Subscriber<List<T>> {
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final CopyOnWriteArrayList<List<T>> values = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch received = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription.set(value);
        }

        @Override
        public void onNext(List<T> value) {
            values.add(value);
            received.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
            failure.set(throwable);
            completed.countDown();
        }

        @Override
        public void onComplete() {
            completed.countDown();
        }
    }
}
