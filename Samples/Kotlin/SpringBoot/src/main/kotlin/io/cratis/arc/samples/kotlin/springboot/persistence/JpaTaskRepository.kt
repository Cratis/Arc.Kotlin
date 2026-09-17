// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot.persistence

import io.cratis.arc.samples.kotlin.springboot.TaskCompletionPreparation
import io.cratis.arc.samples.kotlin.springboot.TaskRepository
import io.cratis.arc.samples.kotlin.springboot.TaskView
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.jpa.repository.JpaRepository

/**
 * The stored shape of a task.
 *
 * `@Version` is doing real work here: it is what makes `CompleteTask`'s optimistic check a database
 * guarantee rather than a hope. Two callers completing the same task race, and exactly one of them
 * finds the version it prepared still current.
 */
@Entity
@Table(name = "tasks")
public class TaskEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    public var id: String = "",

    @Column(name = "title", nullable = false, length = 512)
    public var title: String = "",

    @Column(name = "completed", nullable = false)
    public var completed: Boolean = false,

    @Version
    @Column(name = "version", nullable = false)
    public var version: Long = 0
)

/** Spring Data JPA repository for the task board. */
public interface TaskEntities : JpaRepository<TaskEntity, String>

/**
 * Task storage backed by a relational database through JPA.
 *
 * The observable snapshot is republished after each write this process makes. The Spring Data JPA
 * integration also accepts an explicit change notifier for cases where writes arrive from
 * elsewhere; JPA has no equivalent of a change stream to listen to.
 */
public class JpaTaskRepository(private val entities: TaskEntities) : TaskRepository {
    private val observableTasks: MutableStateFlow<List<TaskView>> = MutableStateFlow(emptyList())

    override fun create(title: String): TaskView {
        val saved = entities.save(TaskEntity(UUID.randomUUID().toString(), title.trim(), completed = false))
        republish()
        return saved.toView()
    }

    override fun prepareCompletion(id: String): TaskCompletionPreparation? =
        entities.findById(id).orElse(null)?.let { entity ->
            TaskCompletionPreparation(entity.toView(), entity.version)
        }

    override fun complete(preparation: TaskCompletionPreparation): TaskView? {
        val current = entities.findById(preparation.task.id).orElse(null) ?: return null
        if (current.version != preparation.revision) return null
        current.completed = true
        val completed = entities.save(current)
        republish()
        return completed.toView()
    }

    override fun byId(id: String): TaskView? = entities.findById(id).orElse(null)?.toView()

    override fun all(): List<TaskView> = snapshot()

    override fun observe(): Flow<List<TaskView>> = observableTasks

    override fun clear() {
        entities.deleteAll()
        republish()
    }

    private fun republish() {
        observableTasks.value = snapshot()
    }

    private fun snapshot(): List<TaskView> = entities.findAll().map(TaskEntity::toView).sortedBy(TaskView::title)
}

private fun TaskEntity.toView(): TaskView = TaskView(id, title, completed)

/** Selects JPA storage when the application runs with the `postgres` profile. */
@Configuration(proxyBeanMethods = false)
@Profile("postgres")
public class JpaTaskRepositoryConfiguration {
    /** Replaces the in-memory store, which backs off because this bean exists. */
    @Bean
    public fun jpaTaskRepository(entities: TaskEntities): TaskRepository = JpaTaskRepository(entities)
}
