```kotlin
@JvmStatic
fun allAuthors(@FromServices queries: MongoObservableQuery): Flow<List<Author>> =
    queries.observe<Author>()
```
