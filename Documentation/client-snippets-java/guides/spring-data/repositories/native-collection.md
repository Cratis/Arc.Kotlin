```java
static void deleteTask(TenantId tenantId, String id, TenantContextMongoAccess access) {
    TenantContextBridge.withTenant(tenantId, () -> {
        MongoCollection<TaskDocument> col = access.collectionForCurrentTenant(TaskDocument.class);
        col.deleteOne(Filters.eq("_id", id));
    });
}
```
