```java
@Command
@CommandEventSourceType("Task")
@CommandEventStreamType("Tasks")
@CommandEventStreamId("active")
@CommandEventSubject("task-owner")
public record CreateTask(@CommandKey String id, String title) {
    public TaskCreated handle() {
        return new TaskCreated(title);
    }
}
```
