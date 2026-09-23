```java
@Component
public final class TaskSource {
    private final ObservableState<List<TaskView>> tasks = new ObservableState<>(List.of());

    public Flow.Publisher<List<TaskView>> observe() {
        return tasks;
    }

    public void publish(List<TaskView> updated) {
        tasks.set(updated);
    }
}
```
