```kotlin
suspend fun archiveTask(tenantId: TenantId, id: String, access: TenantContextMongoAccess) {
    withTenant(tenantId) {
        // operations() reads currentTenant() from the coroutine context on every call.
        val ops = access.operations()
        ops.remove(Query.query(Criteria.where("_id").`is`(id)), TaskDocument::class.java)
    }
}
```
