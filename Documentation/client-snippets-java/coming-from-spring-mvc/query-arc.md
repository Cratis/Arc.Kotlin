```java
// Arc
@ReadModel
@AllowAnonymous
public record TaskView(String id, String title) {
    @Path("/api/tasks")
    public static List<TaskView> all(@FromServices TaskRepository repository) {
        return repository.all();
    }
}
```
