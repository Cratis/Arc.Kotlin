```java
@Command
@AllowAnonymous
public record CreateTask(String title) {
    public TaskCreated handle(TaskRepository repository) {
        var task = repository.create(title);
        return new TaskCreated(task.id(), task.title());
    }
}
```
