```kotlin
@EventType
data class TaskCreated(val title: String)

@Command
data class CreateTask(@CommandKey val id: String, val title: String) {
    fun handle(): TaskCreated = TaskCreated(title)
}
```
