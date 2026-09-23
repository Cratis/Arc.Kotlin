```kotlin
fun observeActiveTasks(
    @FromServices queries: MongoObservableQuery
): Flow<List<TaskView>> =
    queries.observe<TaskView>(Criteria.where("active").`is`(true))
```
