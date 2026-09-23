```kotlin
@Command
data class ArchiveTask(@CommandKey val id: String) {
    suspend fun handle(
        @FromServices access: TenantContextMongoAccess
    ) {
        // operations() reads currentTenant() from the coroutine context — never from construction time.
        val ops = access.operations()
        ops.remove(Query.query(Criteria.where("_id").`is`(id)), TaskDocument::class.java)
    }
}
```
