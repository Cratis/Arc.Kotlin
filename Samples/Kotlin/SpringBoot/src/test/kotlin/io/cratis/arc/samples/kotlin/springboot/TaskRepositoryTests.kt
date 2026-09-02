// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Focused verification of the bounded sample repository. */
public class TaskRepositoryTests {
    @Test
    public fun `repository retains only the 100 most recently created tasks`() {
        val repository = TaskRepository()
        val created = (0..100).map { index ->
            repository.create("Task ${index.toString().padStart(3, '0')}")
        }

        val retained = repository.all()

        assertEquals(100, retained.size)
        assertNull(repository.byId(created.first().id))
        assertEquals(created.drop(1), retained)
        assertEquals(created.last(), repository.byId(created.last().id))
    }

    @Test
    public fun `repository publishes create and completion snapshots`() = runBlocking {
        val repository = TaskRepository()
        assertEquals(emptyList<TaskView>(), repository.observe().first())

        val created = repository.create("Observable task")
        assertEquals(listOf(created), repository.observe().first())

        val completed = repository.complete(requireNotNull(repository.prepareCompletion(created.id)))
        assertEquals(listOf(completed), repository.observe().first())
        assertEquals(true, completed?.completed)
    }

    @Test
    public fun `completion rejects preparation made stale by clear`() = runBlocking {
        val repository = TaskRepository()
        val command = CompleteTask(repository.create("Cleared task").id)
        val preparation = command.provide(repository) as TaskCompletionPreparation

        repository.clear()
        val (response, validationResults) = command.handle(preparation, repository)

        assertEquals(preparation.task, response)
        assertStaleValidation(validationResults)
        assertNull(repository.byId(command.taskId))
    }

    @Test
    public fun `completion rejects preparation made stale by eviction`() = runBlocking {
        val repository = TaskRepository()
        val command = CompleteTask(repository.create("Evicted task").id)
        val preparation = command.provide(repository) as TaskCompletionPreparation
        repeat(100) { index -> repository.create("Replacement $index") }

        val (_, validationResults) = command.handle(preparation, repository)

        assertStaleValidation(validationResults)
        assertNull(repository.byId(command.taskId))
        assertEquals(100, repository.all().size)
    }

    @Test
    public fun `completion rejects preparation made stale by replacement`() = runBlocking {
        val repository = TaskRepository()
        val command = CompleteTask(repository.create("Replaced task").id)
        val stalePreparation = command.provide(repository) as TaskCompletionPreparation
        val winningPreparation = command.provide(repository) as TaskCompletionPreparation
        val (completed, winningValidation) = command.handle(winningPreparation, repository)

        val (_, staleValidation) = command.handle(stalePreparation, repository)

        assertTrue(winningValidation.isEmpty())
        assertTrue(completed.completed)
        assertStaleValidation(staleValidation)
        assertEquals(completed, repository.byId(command.taskId))
    }

    @Test
    public fun `repository snapshots remain bounded ordered and clearable after repeated eviction`() = runBlocking {
        val repository = TaskRepository()
        repeat(250) { index ->
            repository.create("Task ${(249 - index).toString().padStart(3, '0')}")
        }

        val observed = repository.observe().first()

        assertEquals(100, observed.size)
        assertEquals(repository.all(), observed)
        assertEquals(observed.sortedBy(TaskView::title), observed)

        val retainedId = observed.first().id
        repository.clear()

        assertEquals(emptyList<TaskView>(), repository.all())
        assertEquals(emptyList<TaskView>(), repository.observe().first())
        assertNull(repository.byId(retainedId))
    }

    private fun assertStaleValidation(validationResults: List<io.cratis.arc.results.ValidationResult>) {
        assertEquals(1, validationResults.size)
        assertEquals(listOf("taskId"), validationResults.single().members)
        assertFalse(validationResults.single().message.isBlank())
    }
}
