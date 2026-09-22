```java
@ReadModel
@AllowAnonymous
public record TaskView(String id, String title) {
    @Path("/api/tasks/by-id")
    public static TaskView byId(String id, @FromServices TaskRepository repository) {
        return repository.byId(id);
    }

    @Path("/api/tasks")
    public static List<TaskView> all(@FromServices TaskRepository repository) {
        return repository.all();
    }
}
```
