```kotlin
// Arc
@Command
@AllowAnonymous
data class CreateTask(val title: String) {
    fun handle(repository: TaskRepository): TaskCreated {
        val task = repository.create(title)
        return TaskCreated(task.id, task.title)
    }
}
```
