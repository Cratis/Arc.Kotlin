```java
@ReadModel
@AllowAnonymous
public record TaskView(String id, String title) {
    private static final ObservableState<List<TaskView>> tasks = new ObservableState<>(List.of());

    public static Flow.Publisher<List<TaskView>> all() {
        return tasks;
    }
}
```
