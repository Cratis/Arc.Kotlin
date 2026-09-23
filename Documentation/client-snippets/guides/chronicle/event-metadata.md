```kotlin
@Command
@CommandEventSourceType("Task")
@CommandEventStreamType("Tasks")
@CommandEventStreamId("active")
@CommandEventSubject("task-owner")
data class CreateTask(@CommandKey val id: String, val title: String) {
    fun handle(): TaskCreated = TaskCreated(title)
}
```
