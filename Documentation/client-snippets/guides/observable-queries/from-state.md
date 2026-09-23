```kotlin
private val _tasks = MutableStateFlow<List<TaskView>>(emptyList())

@ReadModel
@AllowAnonymous
data class TaskView(val id: String, val title: String) {
    companion object {
        @JvmStatic
        fun all(): Flow<List<TaskView>> = _tasks
    }
}
```
