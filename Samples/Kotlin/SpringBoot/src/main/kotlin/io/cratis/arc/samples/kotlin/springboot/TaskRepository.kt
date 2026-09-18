// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Everything the task board needs from a store.
 *
 * Commands and queries depend on this interface, never on a concrete store. That is what lets
 * `./run.sh --database mongodb` swap the whole persistence layer without a single edit to
 * [CreateTask], [CompleteTask] or [TaskView] — the artifacts describe intent, and where the state
 * lives is a separate decision.
 */
public interface TaskRepository {
    /** Creates and stores a task. */
    public fun create(title: String): TaskView

    /** Captures the current task and its stable revision for completion. */
    public fun prepareCompletion(id: String): TaskCompletionPreparation?

    /** Completes only the exact prepared revision and publishes the committed update. */
    public fun complete(preparation: TaskCompletionPreparation): TaskView?

    /** Gets a task by identifier. */
    public fun byId(id: String): TaskView?

    /** Gets a stable snapshot of every retained task ordered by title. */
    public fun all(): List<TaskView>

    /** Observes stable snapshots of every retained task. */
    public fun observe(): Flow<List<TaskView>>

    /** Clears the sample store. */
    public fun clear()
}

/** Thread-safe, in-memory task storage retaining the 100 most recently created tasks. */
public class InMemoryTaskRepository : TaskRepository {
    private val monitor: Any = Any()
    private val tasks: LinkedHashMap<String, StoredTask> = LinkedHashMap()
    private val observableTasks: MutableStateFlow<List<TaskView>> = MutableStateFlow(emptyList())
    private var nextRevision: Long = 0

    /** Creates and stores a task, evicting the oldest task when the fixed sample bound is exceeded. */
    override fun create(title: String): TaskView {
        val task = TaskView(UUID.randomUUID().toString(), title.trim(), completed = false)
        synchronized(monitor) {
            tasks[task.id] = StoredTask(task, ++nextRevision)
            retainBoundedTasks()
            observableTasks.value = snapshot()
        }
        return task
    }

    override fun prepareCompletion(id: String): TaskCompletionPreparation? = synchronized(monitor) {
        tasks[id]?.let { stored -> TaskCompletionPreparation(stored.task, stored.revision) }
    }

    override fun complete(preparation: TaskCompletionPreparation): TaskView? = synchronized(monitor) {
        val current = tasks[preparation.task.id]
        if (current == null || current.revision != preparation.revision) {
            return@synchronized null
        }

        val completedTask = current.task.copy(completed = true)
        tasks[completedTask.id] = StoredTask(completedTask, ++nextRevision)
        observableTasks.value = snapshot()
        completedTask
    }

    override fun byId(id: String): TaskView? = synchronized(monitor) { tasks[id]?.task }

    override fun all(): List<TaskView> = synchronized(monitor) { snapshot() }

    override fun observe(): Flow<List<TaskView>> = observableTasks

    override fun clear() {
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

/**
 * Supplies the in-memory store unless a database profile selected another one.
 *
 * The condition is a profile expression rather than `@ConditionalOnMissingBean`, which is only
 * reliable inside an auto-configuration: in an application configuration it is evaluated in
 * bean-definition order, so whether it sees the database store depends on component-scan order.
 * That is exactly the kind of "works on my machine" a sample should not teach.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!mongodb & !postgres")
public class InMemoryTaskRepositoryConfiguration {
    /** The default store: no container, no connection string, no setup. */
    @Bean
    public fun inMemoryTaskRepository(): TaskRepository = InMemoryTaskRepository()
}
