// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.springframework.stereotype.Repository

/** Thread-safe, in-memory task storage retaining the 100 most recently created tasks. */
@Repository
public class TaskRepository {
    private val monitor: Any = Any()
    private val tasks: LinkedHashMap<String, StoredTask> = LinkedHashMap()
    private val observableTasks: MutableStateFlow<List<TaskView>> = MutableStateFlow(emptyList())
    private var nextRevision: Long = 0

    /** Creates and stores a task, evicting the oldest task when the fixed sample bound is exceeded. */
    public fun create(title: String): TaskView {
        val task = TaskView(UUID.randomUUID().toString(), title.trim(), completed = false)
        synchronized(monitor) {
            tasks[task.id] = StoredTask(task, ++nextRevision)
            retainBoundedTasks()
            observableTasks.value = snapshot()
        }
        return task
    }

    /** Captures the current task and its stable revision for completion. */
    public fun prepareCompletion(id: String): TaskCompletionPreparation? = synchronized(monitor) {
        tasks[id]?.let { stored -> TaskCompletionPreparation(stored.task, stored.revision) }
    }

    /** Completes only the exact prepared revision and publishes the committed update. */
    public fun complete(preparation: TaskCompletionPreparation): TaskView? = synchronized(monitor) {
        val current = tasks[preparation.task.id]
        if (current == null || current.revision != preparation.revision) {
            return@synchronized null
        }

        val completedTask = current.task.copy(completed = true)
        tasks[completedTask.id] = StoredTask(completedTask, ++nextRevision)
        observableTasks.value = snapshot()
        completedTask
    }

    /** Gets a task by identifier. */
    public fun byId(id: String): TaskView? = synchronized(monitor) { tasks[id]?.task }

    /** Gets a stable snapshot of every retained task ordered by title. */
    public fun all(): List<TaskView> = synchronized(monitor) { snapshot() }

    /** Observes stable snapshots of every retained task. */
    public fun observe(): Flow<List<TaskView>> = observableTasks

    /** Clears the sample store. */
    public fun clear() {
        synchronized(monitor) {
            tasks.clear()
            observableTasks.value = emptyList()
        }
    }

    private fun snapshot(): List<TaskView> = tasks.values.map(StoredTask::task).sortedBy(TaskView::title)

    private fun retainBoundedTasks() {
        if (tasks.size > MAXIMUM_RETAINED_TASKS) {
            tasks.remove(tasks.keys.first())
        }
    }

    private data class StoredTask(val task: TaskView, val revision: Long)

    private companion object {
        const val MAXIMUM_RETAINED_TASKS: Int = 100
    }
}
