```kotlin
suspend fun handle(
    @FromServices access: TenantContextMongoAccess
) {
    val col = access.collection(TaskDocument::class.java)
    col.deleteOne(Filters.eq("_id", id))
}
```
