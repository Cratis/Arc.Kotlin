// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.cratis.arc.queries.ObservableState;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The reason {@link ObservableState} exists is that ordinary Java has no {@code MutableStateFlow}.
 * These calls therefore use only what a Java application would: the constructor, {@code get} and
 * {@code set}, and the {@link Flow.Publisher} contract — no Kotlin property syntax, no coroutines.
 */
final class ObservableStateJavaConformanceTest {
    @Test
    void currentValueIsReadableBeforeAnythingIsPublished() {
        var state = new ObservableState<>("initial");

        assertEquals("initial", state.get());
    }

    @Test
    void setPublishesToEverySubscriberAfterReplayingTheCurrentValue() {
        var state = new ObservableState<>(1);
        var subscriber = new RecordingSubscriber<Integer>();

        state.subscribe(subscriber);
        state.set(2);

        assertEquals(List.of(1, 2), subscriber.values());
        assertNull(subscriber.error());
        assertEquals(2, state.get());
    }

    @Test
    void aSubscriberThatArrivesLateStillSeesTheCurrentValue() {
        var state = new ObservableState<>("initial");
        state.set("changed");
        var subscriber = new RecordingSubscriber<String>();

        state.subscribe(subscriber);

        assertEquals(List.of("changed"), subscriber.values());
    }

    @Test
    void aCancelledSubscriptionStopsReceivingValues() {
        var state = new ObservableState<>(1);
        var subscriber = new RecordingSubscriber<Integer>();
        state.subscribe(subscriber);

        subscriber.cancel();
        state.set(2);

        assertEquals(List.of(1), subscriber.values());
    }

    @Test
    void aJavaQueryMethodCanReturnItAsAPublisher() {
        var state = new ObservableState<>(List.of("one"));

        Flow.Publisher<List<String>> published = state;

        assertInstanceOf(Flow.Publisher.class, published);
    }

    private static final class RecordingSubscriber<T> implements Flow.Subscriber<T> {
        private final CopyOnWriteArrayList<T> received = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription.set(value);
            value.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(T item) {
            received.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            failure.set(throwable);
        }

        @Override
        public void onComplete() {
        }

        List<T> values() {
            return List.copyOf(received);
        }

        Throwable error() {
            return failure.get();
        }

        void cancel() {
            subscription.get().cancel();
        }
    }
}
