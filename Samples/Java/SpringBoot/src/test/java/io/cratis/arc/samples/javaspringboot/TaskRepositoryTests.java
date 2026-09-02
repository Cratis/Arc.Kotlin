// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cratis.arc.results.ValidationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Focused verification of the Java repository's bounded JDK publisher. */
public class TaskRepositoryTests {
    @Test
    public void publisherHonorsDemandReplaysLatestStateAndCleansUpCancellation() {
        var repository = new TaskRepository();
        var subscriber = new RecordingSubscriber();

        TaskView.observe(repository).subscribe(subscriber);

        assertEquals(1, repository.subscriberCount());
        assertTrue(subscriber.values.isEmpty());

        subscriber.request(1);
        assertEquals(List.of(), subscriber.values.get(0));

        var first = repository.create("First observable task");
        repository.create("Second observable task");
        assertEquals(1, subscriber.values.size());

        subscriber.request(1);
        assertEquals(repository.all(), subscriber.values.get(1));
        assertEquals(2, subscriber.values.get(1).size());

        repository.complete(repository.prepareCompletion(first.id()));
        subscriber.request(1);
        assertTrue(subscriber.values.get(2).stream()
            .filter(task -> task.id().equals(first.id()))
            .findFirst()
            .orElseThrow()
            .completed());

        subscriber.cancel();
        assertEquals(0, repository.subscriberCount());
        repository.create("Ignored after cancellation");
        assertEquals(3, subscriber.values.size());
    }

    @Test
    public void completionRejectsPreparationMadeStaleByClear() {
        var repository = new TaskRepository();
        var command = new CompleteTask(repository.create("Cleared task").id());
        var preparation = prepared(command, repository);

        repository.clear();
        var outcome = command.handle(preparation, repository);

        assertEquals(preparation.task(), outcome.getFirst());
        assertStaleValidation(outcome.getSecond());
        assertNull(repository.byId(command.taskId()));
    }

    @Test
    public void completionRejectsPreparationMadeStaleByEviction() {
        var repository = new TaskRepository();
        var command = new CompleteTask(repository.create("Evicted task").id());
        var preparation = prepared(command, repository);
        for (var index = 0; index < 100; index++) {
            repository.create("Replacement " + index);
        }

        var outcome = command.handle(preparation, repository);

        assertStaleValidation(outcome.getSecond());
        assertNull(repository.byId(command.taskId()));
        assertEquals(100, repository.all().size());
    }

    @Test
    public void completionRejectsPreparationMadeStaleByReplacement() {
        var repository = new TaskRepository();
        var command = new CompleteTask(repository.create("Replaced task").id());
        var stalePreparation = prepared(command, repository);
        var winningPreparation = prepared(command, repository);
        var winningOutcome = command.handle(winningPreparation, repository);

        var staleOutcome = command.handle(stalePreparation, repository);

        assertTrue(winningOutcome.getSecond().isEmpty());
        assertTrue(winningOutcome.getFirst().completed());
        assertStaleValidation(staleOutcome.getSecond());
        assertEquals(winningOutcome.getFirst(), repository.byId(command.taskId()));
    }

    @Test
    public void publisherRejectsOutOfOrderGlobalPublishAttempts() {
        var publisher = new TaskRepository.TaskSnapshotPublisher(List.of());
        var newer = new TaskView("newer", "Newer", false);
        var older = new TaskView("older", "Older", false);

        publisher.publish(new TaskRepository.Snapshot(2, List.of(newer)));
        publisher.publish(new TaskRepository.Snapshot(1, List.of(older)));

        var subscriber = new RecordingSubscriber();
        publisher.subscribe(subscriber);
        subscriber.request(1);

        assertEquals(2, publisher.currentSnapshot().version());
        assertEquals(List.of(newer), subscriber.values.get(0));
    }

    @Test
    public void subscriptionRejectsSnapshotsOlderThanItsLatestDeliveredVersion() {
        var subscriber = new RecordingSubscriber();
        var owner = new CopyOnWriteArrayList<TaskRepository.TaskSubscription>();
        var subscription = new TaskRepository.TaskSubscription(subscriber, owner);
        owner.add(subscription);
        subscriber.onSubscribe(subscription);
        var newer = new TaskView("newer", "Newer", false);
        var older = new TaskView("older", "Older", false);

        subscriber.request(1);
        subscription.offer(new TaskRepository.Snapshot(3, List.of(newer)));
        subscription.offer(new TaskRepository.Snapshot(2, List.of(older)));
        subscriber.request(1);

        assertEquals(List.of(List.of(newer)), subscriber.values);
    }

    @Test
    public void reentrantDemandAndPublicationRemainSerialized() {
        var repository = new TaskRepository();
        var subscriber = new ReentrantSubscriber(repository);
        repository.observe().subscribe(subscriber);

        subscriber.request(1);

        assertEquals(2, subscriber.values.size());
        assertEquals(List.of(), subscriber.values.get(0));
        assertEquals(repository.all(), subscriber.values.get(1));
        assertEquals(1, subscriber.maximumCallbackDepth.get());
    }

    @Test
    public void callbackExceptionsAndReentrantCancellationCleanUpSubscriptions() {
        var repository = new TaskRepository();
        var throwingOnNext = new ThrowingOnNextSubscriber();
        repository.observe().subscribe(throwingOnNext);

        assertDoesNotThrow(() -> throwingOnNext.subscription.request(1));
        assertEquals(0, repository.subscriberCount());

        assertDoesNotThrow(() -> repository.observe().subscribe(new ThrowingOnSubscribeSubscriber()));
        assertEquals(0, repository.subscriberCount());

        var cancelling = new CancellingSubscriber();
        repository.observe().subscribe(cancelling);
        cancelling.subscription.request(1);
        repository.create("Ignored after callback cancellation");

        assertEquals(1, cancelling.callbackCount);
        assertEquals(0, repository.subscriberCount());
    }

    @Test
    public void invalidDemandIsSerializedAfterOnNextAndSignalsExactlyOnce() {
        var repository = new TaskRepository();
        var subscriber = new InvalidDemandSubscriber();
        repository.observe().subscribe(subscriber);

        subscriber.subscription.request(1);
        subscriber.subscription.request(-1);
        repository.create("Ignored after terminal signal");

        assertEquals(1, subscriber.nextCount);
        assertEquals(1, subscriber.errorCount);
        assertFalse(subscriber.terminalOverlappedOnNext);
        assertInstanceOf(IllegalArgumentException.class, subscriber.failure);
        assertEquals(0, repository.subscriberCount());
    }

    @Test
    public void terminalCallbackExceptionsAreContainedAndDemandSaturates() {
        var repository = new TaskRepository();
        var throwingTerminal = new ThrowingOnErrorSubscriber();
        repository.observe().subscribe(throwingTerminal);
        assertDoesNotThrow(() -> throwingTerminal.subscription.request(0));
        assertEquals(0, repository.subscriberCount());

        var subscriber = new RecordingSubscriber();
        repository.observe().subscribe(subscriber);
        subscriber.request(Long.MAX_VALUE - 1);
        subscriber.request(10);
        for (var index = 0; index < 5; index++) {
            repository.create("Saturated demand " + index);
        }

        assertEquals(6, subscriber.values.size());
    }

    private static TaskCompletionPreparation prepared(CompleteTask command, TaskRepository repository) {
        return (TaskCompletionPreparation) command.provide(repository).toCompletableFuture().join();
    }

    private static void assertStaleValidation(List<ValidationResult> validationResults) {
        assertEquals(1, validationResults.size());
        assertEquals(List.of("taskId"), validationResults.get(0).getMembers());
        assertFalse(validationResults.get(0).getMessage().isBlank());
    }

    private static class RecordingSubscriber implements Flow.Subscriber<List<TaskView>> {
        protected final List<List<TaskView>> values = new ArrayList<>();
        protected Flow.Subscription subscription;
        protected Throwable failure;

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
        }

        @Override
        public void onNext(List<TaskView> value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable value) {
            failure = value;
        }

        @Override
        public void onComplete() {
        }

        protected void request(long count) {
            subscription.request(count);
        }

        protected void cancel() {
            subscription.cancel();
        }
    }

    private static final class ReentrantSubscriber extends RecordingSubscriber {
        private final TaskRepository repository;
        private final AtomicInteger callbackDepth = new AtomicInteger();
        private final AtomicInteger maximumCallbackDepth = new AtomicInteger();

        private ReentrantSubscriber(TaskRepository repository) {
            this.repository = repository;
        }

        @Override
        public void onNext(List<TaskView> value) {
            var depth = callbackDepth.incrementAndGet();
            maximumCallbackDepth.accumulateAndGet(depth, Math::max);
            try {
                super.onNext(value);
                if (values.size() == 1) {
                    repository.create("Published reentrantly");
                    subscription.request(1);
                }
            } finally {
                callbackDepth.decrementAndGet();
            }
        }
    }

    private static final class ThrowingOnNextSubscriber extends RecordingSubscriber {
        @Override
        public void onNext(List<TaskView> value) {
            throw new IllegalStateException("Subscriber failure");
        }
    }

    private static final class ThrowingOnSubscribeSubscriber extends RecordingSubscriber {
        @Override
        public void onSubscribe(Flow.Subscription value) {
            throw new IllegalStateException("Subscriber failure");
        }
    }

    private static final class CancellingSubscriber extends RecordingSubscriber {
        private int callbackCount;

        @Override
        public void onNext(List<TaskView> value) {
            callbackCount++;
            subscription.cancel();
        }
    }

    private static final class InvalidDemandSubscriber extends RecordingSubscriber {
        private boolean insideOnNext;
        private boolean terminalOverlappedOnNext;
        private int nextCount;
        private int errorCount;

        @Override
        public void onNext(List<TaskView> value) {
            insideOnNext = true;
            nextCount++;
            subscription.request(0);
            insideOnNext = false;
        }

        @Override
        public void onError(Throwable value) {
            terminalOverlappedOnNext = insideOnNext;
            errorCount++;
            failure = value;
        }
    }

    private static final class ThrowingOnErrorSubscriber extends RecordingSubscriber {
        @Override
        public void onError(Throwable value) {
            throw new IllegalStateException("Terminal callback failure");
        }
    }
}
