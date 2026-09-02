// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.authorization.AllowAnonymous;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Creates a task and returns its typed representation. */
@Command
@AllowAnonymous
public record CreateTask(String title) {
    /** Handles the command asynchronously with a repository resolved from Spring. */
    public CompletionStage<TaskCreated> handle(TaskRepository repository) {
        var task = repository.create(title);
        return CompletableFuture.completedFuture(new TaskCreated(task.id(), task.title()));
    }
}
