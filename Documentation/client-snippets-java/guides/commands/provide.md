```java
@Command
public record CompleteTask(TaskId taskId) {
    public Task provide(Tasks tasks) {
        return tasks.get(taskId);
    }

    public TaskCompleted handle(Task task, AuditLog audit) {
        audit.record(task.id());
        return new TaskCompleted(task.id());
    }
}
```
