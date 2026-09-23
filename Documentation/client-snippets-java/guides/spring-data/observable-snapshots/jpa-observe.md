```java
public static Flow.Publisher<List<TaskView>> observeTasks(@FromServices JpaObservableQuery queries) {
    return JpaObservations.observePublisher(queries, TaskView.class);
}
```
