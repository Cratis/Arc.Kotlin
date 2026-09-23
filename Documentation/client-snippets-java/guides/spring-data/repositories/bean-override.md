```java
@Bean
public TenantContextMongoAccess arcTenantContextMongoAccess(
    TenantAwareMongoOperationsResolver resolver,
    NamingPolicy namingPolicy
) {
    return new TenantContextMongoAccess(resolver, namingPolicy, TenantId.DEFAULT);
}
```
