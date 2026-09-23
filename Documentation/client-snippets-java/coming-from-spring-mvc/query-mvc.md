```java
// Spring MVC
@RestController
public class TaskQueryController {
    private final TaskRepository repository;

    public TaskQueryController(TaskRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/tasks")
    public List<TaskView> all() {
        return repository.all();
    }
}
```
