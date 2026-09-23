```java
@Command
public record RenameTask(@CommandKey String id, String title) {
    public void handle(TaskView current) {
        // current came from the owning persistence provider using id.
    }
}
```
