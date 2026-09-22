```kotlin
@ReadModel
@AllowAnonymous
data class TaskView(val id: String, val title: String) {
    companion object {
        @JvmStatic
        @Path("/api/tasks/by-id")
        suspend fun byId(id: String, @FromServices repository: TaskRepository): TaskView? = repository.byId(id)

        @JvmStatic
        @Path("/api/tasks")
        fun all(@FromServices repository: TaskRepository): List<TaskView> = repository.all()
    }
}
```
