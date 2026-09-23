```kotlin
@Component
class TaskSource {
    private val tasks = ObservableState<List<TaskView>>(emptyList())

    fun observe(): Flow.Publisher<List<TaskView>> = tasks

    fun publish(updated: List<TaskView>) {
        tasks.set(updated)
    }
}
```
