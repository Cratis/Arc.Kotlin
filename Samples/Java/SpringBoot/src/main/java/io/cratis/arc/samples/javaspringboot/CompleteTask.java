// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot;

import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.CommandKey;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.results.ValidationResultSeverity;
import java.util.List;
import java.util.concurrent.CompletionStage;
import kotlin.Pair;

/** Completes an existing task through Arc's generated provide-to-handle lifecycle. */
@Command
@AllowAnonymous
public record CompleteTask(@CommandKey String taskId) {
    /** Loads the task asynchronously during provide or returns normal validation feedback. */
    public CompletionStage<Object> provide(TaskRepository repository) {
        return repository.prepareCompletionAsync(taskId).thenApply(preparation -> preparation == null
            ? validation("The task does not exist.")
            : preparation);
    }

    /** Completes the exact prepared revision or returns normal validation feedback when it became stale. */
    public Pair<TaskView, List<ValidationResult>> handle(
        TaskCompletionPreparation preparation,
        TaskRepository repository) {
        var completed = repository.complete(preparation);
        return completed == null
            ? new Pair<>(
                preparation.task(),
                List.of(validation("The task changed or is no longer available. Try again.")))
            : new Pair<>(completed, List.of());
    }

    private static ValidationResult validation(String message) {
        return new ValidationResult(
            ValidationResultSeverity.Error,
            message,
            List.of("taskId"));
    }
}
