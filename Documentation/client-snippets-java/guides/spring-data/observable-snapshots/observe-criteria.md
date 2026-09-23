```java
Flow.Publisher<List<TaskView>> publisher =
    MongoObservations.observe(queries, TaskView.class, Criteria.where("active").is(true));

Flow.Publisher<TaskView> single =
    MongoObservations.observeSingle(queries, TaskView.class, Criteria.where("id").is(taskId));
```
