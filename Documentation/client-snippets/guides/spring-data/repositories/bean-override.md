```kotlin
// Application bean override — use the workspace default tenant when no tenant is active.
@Bean
fun arcTenantContextMongoAccess(
    resolver: TenantAwareMongoOperationsResolver,
    namingPolicy: NamingPolicy
): TenantContextMongoAccess = TenantContextMongoAccess(resolver, namingPolicy, TenantId.DEFAULT)
```
