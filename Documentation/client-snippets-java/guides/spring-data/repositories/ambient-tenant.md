```java
static void archiveTask(TenantId tenantId, String id, TenantContextMongoAccess access) {
    TenantContextBridge.withTenant(tenantId, () -> {
        // operationsForCurrentTenant() reads the tenant the bridge established for this call.
        MongoOperations ops = access.operationsForCurrentTenant();
        ops.remove(Query.query(Criteria.where("_id").is(id)), TaskDocument.class);
    });
}
```
