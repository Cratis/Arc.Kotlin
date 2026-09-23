```java
public void handle(@FromServices TenantContextMongoAccess access) {
    MongoCollection<TaskDocument> col = access.collectionForCurrentTenant(TaskDocument.class);
    col.deleteOne(Filters.eq("_id", id));
}
```
