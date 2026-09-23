```kotlin
suspend fun handleRequest() {
    withTenant(TenantId("acme")) {
        // currentTenant() returns TenantId("acme") here
        performWork()
    }
    // currentTenant() returns null here — scope is restored on exit
}
```
