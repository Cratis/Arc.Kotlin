// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.persistence;

import io.cratis.arc.queries.ObservableState;
import io.cratis.arc.samples.javaspringboot.TaskCompletionPreparation;
import io.cratis.arc.samples.javaspringboot.TaskStore;
import io.cratis.arc.samples.javaspringboot.TaskView;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Task storage backed by a relational database through JPA.
 *
 * The observable snapshot is republished after each write this process makes. The Spring Data JPA
 * integration also accepts an explicit change notifier for cases where writes arrive from elsewhere;
 * JPA has no equivalent of a change stream to listen to.
 */
public final class JpaTaskStore implements TaskStore {
    private final TaskEntities entities;
    private final ObservableState<List<TaskView>> observableTasks = new ObservableState<>(List.of());

    /**
     * Initializes a new instance of the {@link JpaTaskStore} class.
     *
     * @param entities The Spring Data repository.
     */
    public JpaTaskStore(TaskEntities entities) {
        this.entities = entities;
    }

    @Override
    public TaskView create(String title) {
        var saved = entities.save(new TaskEntity(UUID.randomUUID().toString(), title.trim(), false));
        republish();
        return toView(saved);
    }

    @Override
    public TaskCompletionPreparation prepareCompletion(String id) {
        return entities.findById(id)
            .map(entity -> new TaskCompletionPreparation(toView(entity), entity.getVersion()))
            .orElse(null);
    }

    @Override
    public CompletionStage<TaskCompletionPreparation> prepareCompletionAsync(String id) {
        return CompletableFuture.completedFuture(prepareCompletion(id));
    }

    @Override
    public TaskView complete(TaskCompletionPreparation preparation) {
        var current = entities.findById(preparation.task().id()).orElse(null);
        if (current == null || current.getVersion() != preparation.revision()) {
            return null;
        }
        current.setCompleted(true);
        var completed = entities.save(current);
        republish();
        return toView(completed);
    }

    @Override
    public TaskView byId(String id) {
        return entities.findById(id).map(JpaTaskStore::toView).orElse(null);
    }

    @Override
    public CompletionStage<TaskView> byIdAsync(String id) {
        return CompletableFuture.completedFuture(byId(id));
    }

    @Override
    public List<TaskView> all() {
        return snapshot();
    }

    @Override
    public Flow.Publisher<List<TaskView>> observe() {
        return observableTasks;
    }

    @Override
    public void clear() {
        entities.deleteAll();
        republish();
    }

    private void republish() {
        observableTasks.set(snapshot());
    }

    private List<TaskView> snapshot() {
        return entities.findAll().stream()
            .map(JpaTaskStore::toView)
            .sorted(Comparator.comparing(TaskView::title))
            .toList();
    }

    private static TaskView toView(TaskEntity entity) {
        return new TaskView(entity.getId(), entity.getTitle(), entity.isCompleted());
    }
}
