// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Repository;

/** Thread-safe, in-memory task storage retaining the 100 most recently created tasks. */
@Repository
public final class TaskRepository {
    private static final int MAXIMUM_RETAINED_TASKS = 100;

    private final Object monitor = new Object();
    private final LinkedHashMap<String, StoredTask> tasks = new LinkedHashMap<>();
    private final TaskSnapshotPublisher observableTasks = new TaskSnapshotPublisher(List.of());
    private long stateVersion;

    /** Creates and stores a task, evicting the oldest task when the sample bound is exceeded. */
    public TaskView create(String title) {
        var task = new TaskView(UUID.randomUUID().toString(), title.trim(), false);
        Snapshot committedSnapshot;
        synchronized (monitor) {
            var version = ++stateVersion;
            tasks.put(task.id(), new StoredTask(task, version));
            retainBoundedTasks();
            committedSnapshot = new Snapshot(version, snapshot());
        }
        observableTasks.publish(committedSnapshot);
        return task;
    }

    /** Captures the current task and its stable revision for completion. */
    public TaskCompletionPreparation prepareCompletion(String id) {
        synchronized (monitor) {
            var stored = tasks.get(id);
            return stored == null ? null : new TaskCompletionPreparation(stored.task(), stored.revision());
        }
    }

    /** Captures completion state through a Java asynchronous API. */
    public CompletionStage<TaskCompletionPreparation> prepareCompletionAsync(String id) {
        return CompletableFuture.completedFuture(prepareCompletion(id));
    }

    /** Completes only the exact prepared revision and publishes the committed update. */
    public TaskView complete(TaskCompletionPreparation preparation) {
        TaskView completedTask;
        Snapshot committedSnapshot;
        synchronized (monitor) {
            var current = tasks.get(preparation.task().id());
            if (current == null || current.revision() != preparation.revision()) {
                return null;
            }

            completedTask = new TaskView(current.task().id(), current.task().title(), true);
            var version = ++stateVersion;
            tasks.put(completedTask.id(), new StoredTask(completedTask, version));
            committedSnapshot = new Snapshot(version, snapshot());
        }
        observableTasks.publish(committedSnapshot);
        return completedTask;
    }

    /** Gets a task by identifier. */
    public TaskView byId(String id) {
        synchronized (monitor) {
            var stored = tasks.get(id);
            return stored == null ? null : stored.task();
        }
    }

    /** Gets a task by identifier through a Java asynchronous API. */
    public CompletionStage<TaskView> byIdAsync(String id) {
        return CompletableFuture.completedFuture(byId(id));
    }

    /** Gets a stable snapshot of every retained task ordered by title. */
    public List<TaskView> all() {
        synchronized (monitor) {
            return snapshot();
        }
    }

    /** Observes bounded, replayable snapshots using the JDK Flow API. */
    public Flow.Publisher<List<TaskView>> observe() {
        return observableTasks;
    }

    /** Clears the sample store and publishes the committed empty snapshot. */
    public void clear() {
        Snapshot committedSnapshot;
        synchronized (monitor) {
            tasks.clear();
            committedSnapshot = new Snapshot(++stateVersion, List.of());
        }
        observableTasks.publish(committedSnapshot);
    }

    int subscriberCount() {
        return observableTasks.subscriberCount();
    }

    private List<TaskView> snapshot() {
        var snapshot = tasks.values().stream().map(StoredTask::task).collect(ArrayList<TaskView>::new, List::add, List::addAll);
        snapshot.sort(Comparator.comparing(TaskView::title));
        return List.copyOf(snapshot);
    }

    private void retainBoundedTasks() {
        if (tasks.size() > MAXIMUM_RETAINED_TASKS) {
            tasks.remove(tasks.keySet().iterator().next());
        }
    }

    private record StoredTask(TaskView task, long revision) {
    }

    static final class TaskSnapshotPublisher implements Flow.Publisher<List<TaskView>> {
        private final AtomicReference<Snapshot> current;
        private final CopyOnWriteArrayList<TaskSubscription> subscriptions = new CopyOnWriteArrayList<>();

        TaskSnapshotPublisher(List<TaskView> initial) {
            current = new AtomicReference<>(new Snapshot(0, initial));
        }

        @Override
        public void subscribe(Flow.Subscriber<? super List<TaskView>> subscriber) {
            Objects.requireNonNull(subscriber, "subscriber");
            var subscription = new TaskSubscription(subscriber, subscriptions);
            subscriptions.add(subscription);
            subscription.offer(current.get());
            try {
                subscriber.onSubscribe(subscription);
            } catch (RuntimeException ignored) {
                subscription.cancel();
            }
        }

        void publish(Snapshot snapshot) {
            while (true) {
                var observed = current.get();
                if (snapshot.version() <= observed.version()) {
                    return;
                }
                if (current.compareAndSet(observed, snapshot)) {
                    break;
                }
            }
            subscriptions.forEach(subscription -> subscription.offer(snapshot));
        }

        int subscriberCount() {
            return subscriptions.size();
        }

        Snapshot currentSnapshot() {
            return current.get();
        }
    }

    record Snapshot(long version, List<TaskView> tasks) {
        Snapshot {
            tasks = List.copyOf(tasks);
        }
    }

    static final class TaskSubscription implements Flow.Subscription {
        private static final Object ACTIVE = new Object();
        private static final Object CANCELLED = new Object();
        private static final Object TERMINATED = new Object();

        private final Flow.Subscriber<? super List<TaskView>> subscriber;
        private final CopyOnWriteArrayList<TaskSubscription> owner;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicReference<Snapshot> pending = new AtomicReference<>();
        private final AtomicInteger draining = new AtomicInteger();
        private final AtomicReference<Object> lifecycle = new AtomicReference<>(ACTIVE);
        private final Object offerMonitor = new Object();
        private long latestOfferedVersion = -1;

        TaskSubscription(
            Flow.Subscriber<? super List<TaskView>> subscriber,
            CopyOnWriteArrayList<TaskSubscription> owner) {
            this.subscriber = subscriber;
            this.owner = owner;
        }

        @Override
        public void request(long count) {
            if (count <= 0) {
                fail(new IllegalArgumentException("Flow demand must be positive."));
                return;
            }
            if (lifecycle.get() != ACTIVE) {
                return;
            }
            addDemand(count);
            drain();
        }

        @Override
        public void cancel() {
            if (lifecycle.compareAndSet(ACTIVE, CANCELLED)) {
                pending.set(null);
                owner.remove(this);
            }
        }

        void offer(Snapshot snapshot) {
            synchronized (offerMonitor) {
                if (lifecycle.get() != ACTIVE || snapshot.version() <= latestOfferedVersion) {
                    return;
                }
                latestOfferedVersion = snapshot.version();
                pending.set(snapshot);
            }
            drain();
        }

        private void addDemand(long count) {
            while (true) {
                var existing = demand.get();
                var updated = existing > Long.MAX_VALUE - count ? Long.MAX_VALUE : existing + count;
                if (demand.compareAndSet(existing, updated)) {
                    return;
                }
            }
        }

        private void consumeDemand() {
            while (true) {
                var existing = demand.get();
                if (existing == Long.MAX_VALUE) {
                    return;
                }
                if (demand.compareAndSet(existing, existing - 1)) {
                    return;
                }
            }
        }

        private void drain() {
            if (draining.getAndIncrement() != 0) {
                return;
            }
            var missed = 1;
            do {
                var state = lifecycle.get();
                if (state instanceof Failure failure) {
                    pending.set(null);
                    if (lifecycle.compareAndSet(state, TERMINATED)) {
                        try {
                            subscriber.onError(failure.exception());
                        } catch (RuntimeException ignored) {
                            // Subscriber callbacks cannot compromise repository publication.
                        }
                    }
                } else if (state == ACTIVE && demand.get() > 0) {
                    var snapshot = pending.getAndSet(null);
                    if (snapshot != null && lifecycle.get() == ACTIVE) {
                        consumeDemand();
                        try {
                            subscriber.onNext(snapshot.tasks());
                        } catch (RuntimeException ignored) {
                            cancel();
                        }
                    }
                } else if (state != ACTIVE) {
                    pending.set(null);
                }
                missed = draining.addAndGet(-missed);
            } while (missed != 0);
        }

        private void fail(RuntimeException exception) {
            if (lifecycle.compareAndSet(ACTIVE, new Failure(exception))) {
                pending.set(null);
                owner.remove(this);
                drain();
            }
        }

        private record Failure(RuntimeException exception) {
        }
    }
}
