```kotlin
// Spring MVC
@RestController
class TaskQueryController(private val repository: TaskRepository) {
    @GetMapping("/api/tasks")
    fun all(): List<TaskView> = repository.all()
}
```
