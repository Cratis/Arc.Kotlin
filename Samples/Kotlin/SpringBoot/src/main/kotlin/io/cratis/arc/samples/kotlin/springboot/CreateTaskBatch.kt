// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.artifacts.Command
import io.cratis.arc.authorization.AllowAnonymous

/** Creates a bounded batch of tasks and returns each typed task representation. */
@Command
@AllowAnonymous
public data class CreateTaskBatch(public val titles: List<String>) {
    /** Handles the command with a repository resolved from Spring. */
    public fun handle(repository: TaskRepository): Pair<List<TaskCreated>, TaskBatchCreationHandled> {
        require(titles.size in 1..MAXIMUM_BATCH_SIZE) {
            "A task batch must contain between 1 and $MAXIMUM_BATCH_SIZE titles."
        }
        titles.forEach(TaskTitleRule::requireValid)
        val tasks = titles.map(repository::create)
        return Pair(
            tasks.map { task -> TaskCreated(task.id, task.title) },
            TaskBatchCreationHandled(tasks.map(TaskView::id))
        )
    }

    internal companion object {
        const val MAXIMUM_BATCH_SIZE: Int = 3
    }
}
