```kotlin
@JvmStatic
fun observeTasks(
    @FromServices queries: JpaObservableQuery
): Flow<List<TaskView>> = queries.observe(TaskView::class.java)
```
