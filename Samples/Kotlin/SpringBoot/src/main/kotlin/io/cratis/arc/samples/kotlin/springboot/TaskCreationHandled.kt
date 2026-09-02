// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandResponseValueHandler
import io.cratis.arc.commands.HandlesCommandResponseValues
import io.cratis.arc.results.CommandResult
import java.util.concurrent.atomic.AtomicReference
import org.springframework.stereotype.Component

/** Server-consumed value emitted after a task has been created. */
public data class TaskCreationHandled(public val taskId: String)

/** Server-consumed value emitted after a bounded batch of tasks has been created. */
public data class TaskBatchCreationHandled(public val taskIds: List<String>)

/** Records the most recent task-creation response values consumed inside the command pipeline. */
@Component
@HandlesCommandResponseValues(TaskCreationHandled::class, TaskBatchCreationHandled::class)
public class TaskCreationResponseValueHandler : CommandResponseValueHandler {
    private val lastHandledTaskId: AtomicReference<String?> = AtomicReference()
    private val lastHandledBatchTaskIds: AtomicReference<List<String>> = AtomicReference(emptyList())

    override fun canHandle(context: CommandContext, value: Any): Boolean =
        value is TaskCreationHandled || value is TaskBatchCreationHandled

    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
        when (value) {
            is TaskCreationHandled -> lastHandledTaskId.set(value.taskId)
            is TaskBatchCreationHandled -> lastHandledBatchTaskIds.set(value.taskIds.toList())
            else -> return CommandResult.error(context.correlationId, "Unsupported task-creation response value.")
        }
        return CommandResult.success(context.correlationId)
    }

    /** Returns whether this bean most recently consumed the response value for a task. */
    internal fun hasHandled(taskId: String): Boolean = lastHandledTaskId.get() == taskId

    /** Returns whether this bean most recently consumed the response value for a task batch. */
    internal fun hasHandledBatch(taskIds: List<String>): Boolean = lastHandledBatchTaskIds.get() == taskIds

    /** Clears recorded sample state. */
    internal fun clear() {
        lastHandledTaskId.set(null)
        lastHandledBatchTaskIds.set(emptyList())
    }
}
