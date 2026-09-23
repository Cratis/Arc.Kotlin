```java
@Command
public record ArchiveTask(@CommandKey String id) {
    public void handle(@FromServices TenantContextMongoAccess access) {
        // operationsForCurrentTenant() reads the tenant kept in sync for blocking call paths —
        // never from construction time.
        MongoOperations ops = access.operationsForCurrentTenant();
        ops.remove(Query.query(Criteria.where("_id").is(id)), TaskDocument.class);
    }
}
```
