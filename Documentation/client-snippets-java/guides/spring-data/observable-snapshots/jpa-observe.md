```java
Flow.Publisher<List<TaskView>> publisher =
    JpaObservations.observePublisher(queries, TaskView.class);
```
