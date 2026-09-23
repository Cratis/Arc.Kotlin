```kotlin
suspend fun deleteTask(tenantId: TenantId, id: String, access: TenantContextMongoAccess) {
    withTenant(tenantId) {
        val col = access.collection(TaskDocument::class.java)
        col.deleteOne(Filters.eq("_id", id))
    }
}
```
