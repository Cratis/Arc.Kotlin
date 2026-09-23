```kotlin
suspend fun nestedScopes() {
    withTenant(TenantId("outer")) {
        withTenant(TenantId("inner")) {
            currentTenant()  // TenantId("inner")
        }
        currentTenant()      // TenantId("outer") — restored
    }
}
```
