```kotlin
@ReadModel
@AllowAnonymous
data class TaskView(val id: String, val title: String) {
    companion object {
        @JvmStatic
        fun all(@FromServices repository: TaskRepository): Flow<List<TaskView>> =
            repository.observeAll()
    }
}
```
