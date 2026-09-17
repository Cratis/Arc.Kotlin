// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Everything the task board needs from a store.
 *
 * Commands and queries depend on this interface, never on a concrete store. That is what lets
 * {@code ./run.sh --database mongodb} swap the whole persistence layer without a single edit to
 * {@code CreateTask}, {@code CompleteTask} or {@code TaskView} — the artifacts describe intent, and
 * where the state lives is a separate decision.
 */
public interface TaskStore {
    /**
     * Creates and stores a task.
     *
     * @param title The task title.
     * @return The created task.
     */
    TaskView create(String title);

    /**
     * Captures the current task and its stable revision for completion.
     *
     * @param id The task identifier.
     * @return The preparation, or null when the task does not exist.
     */
    TaskCompletionPreparation prepareCompletion(String id);

    /**
     * Captures completion state through a Java asynchronous API.
     *
     * @param id The task identifier.
     * @return The preparation, or null when the task does not exist.
     */
    CompletionStage<TaskCompletionPreparation> prepareCompletionAsync(String id);

    /**
     * Completes only the exact prepared revision.
     *
     * @param preparation The preparation captured earlier.
     * @return The completed task, or null when the preparation went stale.
     */
    TaskView complete(TaskCompletionPreparation preparation);

    /**
     * Gets a task by identifier.
     *
     * @param id The task identifier.
     * @return The task, or null when it does not exist.
     */
    TaskView byId(String id);

    /**
     * Gets a task by identifier through a Java asynchronous API.
     *
     * @param id The task identifier.
     * @return The task, or null when it does not exist.
     */
    CompletionStage<TaskView> byIdAsync(String id);

    /**
     * Gets a stable snapshot of every retained task ordered by title.
     *
     * @return Every retained task.
     */
    List<TaskView> all();

    /**
     * Observes stable snapshots of every retained task.
     *
     * @return A publisher of task snapshots.
     */
    Flow.Publisher<List<TaskView>> observe();

    /** Clears the sample store. */
    void clear();
}
