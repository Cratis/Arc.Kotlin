```kotlin
@Command
data class RenameTask(@CommandKey val id: String, val title: String) {
    fun handle(current: TaskView) {
        // current came from the owning persistence provider using id.
    }
}
```
