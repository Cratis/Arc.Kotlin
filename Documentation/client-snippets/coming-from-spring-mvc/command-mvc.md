```kotlin
// Spring MVC
@RestController
class TaskController(private val repository: TaskRepository) {
    @PostMapping("/api/create-task")
    fun create(@Valid @RequestBody request: CreateTaskRequest): ResponseEntity<TaskCreated> {
        val task = repository.create(request.title)
        return ResponseEntity.ok(TaskCreated(task.id, task.title))
    }
}
```
