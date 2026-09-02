// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity

/** State and stable repository revision loaded during command preparation. */
public data class TaskCompletionPreparation(public val task: TaskView, public val revision: Long)

/** Completes an existing task through Arc's generated provide-to-handle lifecycle. */
@Command
@AllowAnonymous
public data class CompleteTask(@CommandKey public val taskId: String) {
    /** Loads the task during the suspending provide phase or returns normal validation feedback. */
    public suspend fun provide(repository: TaskRepository): Any = repository.prepareCompletion(taskId)
        ?: validation("The task does not exist.")

    /** Completes the exact prepared revision or returns normal validation feedback when it became stale. */
    public fun handle(
        preparation: TaskCompletionPreparation,
        repository: TaskRepository
    ): Pair<TaskView, List<ValidationResult>> {
        val completed = repository.complete(preparation)
        return if (completed == null) {
            preparation.task to listOf(validation("The task changed or is no longer available. Try again."))
        } else {
            completed to emptyList()
        }
    }

    private fun validation(message: String): ValidationResult = ValidationResult(
        ValidationResultSeverity.Error,
        message,
        listOf("taskId")
    )
}
