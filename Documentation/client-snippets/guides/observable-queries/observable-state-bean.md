```kotlin
@Component
class TaskSource {
    private val tasks = MutableStateFlow<List<TaskView>>(emptyList())

    fun observe(): Flow<List<TaskView>> = tasks

    fun publish(updated: List<TaskView>) {
        tasks.value = updated
    }
}
```
