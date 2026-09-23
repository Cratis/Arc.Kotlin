```java
@ReadModel
@AllowAnonymous
public record TaskView(String id, String title) {
    public static java.util.concurrent.Flow.Publisher<List<TaskView>> all(
        @FromServices TaskRepository repository
    ) {
        return repository.observeAll();
    }
}
```
