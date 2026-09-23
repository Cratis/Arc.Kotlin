```java
public static Flow.Publisher<List<TaskView>> observeActiveTasks(@FromServices MongoObservableQuery queries) {
    return MongoObservations.observe(queries, TaskView.class, Criteria.where("active").is(true));
}
```
