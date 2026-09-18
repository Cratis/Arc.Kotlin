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
 * Task storage backed by MongoDB.
 *
 * The observable snapshot is republished after each write this process makes. The Spring Data
 * MongoDB integration can instead back an observable query with a change stream, so a write from
 * another process is picked up too — that needs a replica set, which this sample's single-node
 * container deliberately is not.
 */
public final class MongoTaskStore implements TaskStore {
    private final TaskDocuments documents;
    private final ObservableState<List<TaskView>> observableTasks;

    /**
     * Initializes a new instance of the {@link MongoTaskStore} class.
     *
     * @param documents The Spring Data repository.
     */
    public MongoTaskStore(TaskDocuments documents) {
        this.documents = documents;
        this.observableTasks = new ObservableState<>(snapshot());
    }

    @Override
    public TaskView create(String title) {
        var saved = documents.save(new TaskDocument(UUID.randomUUID().toString(), title.trim(), false, null));
        republish();
        return toView(saved);
    }

    @Override
    public TaskCompletionPreparation prepareCompletion(String id) {
        return documents.findById(id)
            .map(document -> new TaskCompletionPreparation(toView(document), version(document)))
            .orElse(null);
    }

    @Override
    public CompletionStage<TaskCompletionPreparation> prepareCompletionAsync(String id) {
        return CompletableFuture.completedFuture(prepareCompletion(id));
    }

    @Override
    public TaskView complete(TaskCompletionPreparation preparation) {
        var current = documents.findById(preparation.task().id()).orElse(null);
        if (current == null || version(current) != preparation.revision()) {
            return null;
        }
        var completed = documents.save(new TaskDocument(current.id(), current.title(), true, current.version()));
        republish();
        return toView(completed);
    }

    @Override
    public TaskView byId(String id) {
        return documents.findById(id).map(MongoTaskStore::toView).orElse(null);
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
        documents.deleteAll();
        republish();
    }

    private void republish() {
        observableTasks.set(snapshot());
    }

    private List<TaskView> snapshot() {
        return documents.findAll().stream()
            .map(MongoTaskStore::toView)
            .sorted(Comparator.comparing(TaskView::title))
            .toList();
    }

    private static long version(TaskDocument document) {
        return document.version() == null ? 0 : document.version();
    }

    private static TaskView toView(TaskDocument document) {
        return new TaskView(document.id(), document.title(), document.completed());
    }
}
