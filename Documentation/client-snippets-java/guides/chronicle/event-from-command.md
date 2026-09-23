```java
@EventType
public record TaskCreated(String title) { }

@Command
public record CreateTask(@CommandKey String id, String title) {
    public TaskCreated handle() {
        return new TaskCreated(title);
    }
}
```
