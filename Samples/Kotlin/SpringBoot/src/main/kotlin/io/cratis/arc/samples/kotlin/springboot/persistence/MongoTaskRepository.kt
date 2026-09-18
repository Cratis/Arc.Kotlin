// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot.persistence

import io.cratis.arc.samples.kotlin.springboot.TaskCompletionPreparation
import io.cratis.arc.samples.kotlin.springboot.TaskRepository
import io.cratis.arc.samples.kotlin.springboot.TaskView
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository

/**
 * The stored shape of a task.
 *
 * `@Version` is doing real work here: it is what makes `CompleteTask`'s optimistic check a database
 * guarantee rather than a hope. Two callers completing the same task race, and exactly one of them
 * finds the version it prepared still current.
 *
 * @property id The task identifier.
 * @property title The task title.
 * @property completed Whether the task has been completed.
 * @property version The optimistic-concurrency version Spring Data maintains.
 */
@Document("tasks")
public data class TaskDocument(
    @Id public val id: String,
    public val title: String,
    public val completed: Boolean,
    @Version public val version: Long? = null
)

/** Spring Data MongoDB repository for the task board. */
public interface TaskDocuments : MongoRepository<TaskDocument, String>

/**
 * Task storage backed by MongoDB.
 *
 * The observable snapshot is republished after each write this process makes. The Spring Data
 * MongoDB integration can instead back an observable query with a change stream, so a write from
 * another process is picked up too — that needs a replica set, which this sample's single-node
 * container deliberately is not.
 */
public class MongoTaskRepository(private val documents: TaskDocuments) : TaskRepository {
    private val observableTasks: MutableStateFlow<List<TaskView>> = MutableStateFlow(snapshot())

    override fun create(title: String): TaskView {
        val saved = documents.save(TaskDocument(UUID.randomUUID().toString(), title.trim(), completed = false))
        republish()
        return saved.toView()
    }

    override fun prepareCompletion(id: String): TaskCompletionPreparation? =
        documents.findById(id).orElse(null)?.let { document ->
            TaskCompletionPreparation(document.toView(), document.version ?: 0)
        }

    override fun complete(preparation: TaskCompletionPreparation): TaskView? {
        val current = documents.findById(preparation.task.id).orElse(null) ?: return null
        if ((current.version ?: 0) != preparation.revision) return null
        val completed = documents.save(current.copy(completed = true))
        republish()
        return completed.toView()
    }

    override fun byId(id: String): TaskView? = documents.findById(id).orElse(null)?.toView()

    override fun all(): List<TaskView> = snapshot()

    override fun observe(): Flow<List<TaskView>> = observableTasks

    override fun clear() {
        documents.deleteAll()
        republish()
    }

    private fun republish() {
        observableTasks.value = snapshot()
    }

    private fun snapshot(): List<TaskView> = documents.findAll().map(TaskDocument::toView).sortedBy(TaskView::title)
}

private fun TaskDocument.toView(): TaskView = TaskView(id, title, completed)

/** Selects MongoDB storage when the application runs with the `mongodb` profile. */
@Configuration(proxyBeanMethods = false)
@Profile("mongodb")
public class MongoTaskRepositoryConfiguration {
    /** Replaces the in-memory store, which backs off because this bean exists. */
    @Bean
    public fun mongoTaskRepository(documents: TaskDocuments): TaskRepository = MongoTaskRepository(documents)
}
