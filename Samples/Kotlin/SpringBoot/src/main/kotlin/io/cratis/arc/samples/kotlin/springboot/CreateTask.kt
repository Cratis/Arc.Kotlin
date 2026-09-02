// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.artifacts.Command
import io.cratis.arc.authorization.AllowAnonymous

/** Creates a task and returns its typed representation while emitting a server-consumed value. */
@Command
@AllowAnonymous
public data class CreateTask(public val title: String) {
    /** Handles the command with a repository resolved from Spring. */
    public fun handle(repository: TaskRepository): Pair<TaskCreated, TaskCreationHandled> {
        TaskTitleRule.requireValid(title)
        val task = repository.create(title)
        return Pair(
            TaskCreated(task.id, task.title),
            TaskCreationHandled(task.id)
        )
    }
}
