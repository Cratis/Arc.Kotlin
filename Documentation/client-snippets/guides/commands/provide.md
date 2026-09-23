```kotlin
@Command
data class CompleteTask(val taskId: TaskId) {
    suspend fun provide(tasks: Tasks): Task = tasks.get(taskId)

    fun handle(task: Task, audit: AuditLog): TaskCompleted {
        audit.record(task.id)
        return TaskCompleted(task.id)
    }
}
```
