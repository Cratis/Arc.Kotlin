```java
// Spring MVC
@RestController
public class TaskController {
    private final TaskRepository repository;

    public TaskController(TaskRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/api/create-task")
    public ResponseEntity<TaskCreated> create(@Valid @RequestBody CreateTaskRequest request) {
        var task = repository.create(request.title());
        return ResponseEntity.ok(new TaskCreated(task.id(), task.title()));
    }
}
```
