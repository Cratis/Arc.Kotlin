```kotlin
// Arc
@ReadModel
@AllowAnonymous
data class TaskView(val id: String, val title: String) {
    companion object {
        @JvmStatic
        @Path("/api/tasks")
        fun all(@FromServices repository: TaskRepository): List<TaskView> = repository.all()
    }
}
```
