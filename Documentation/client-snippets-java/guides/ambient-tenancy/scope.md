```java
void handleRequest(TenantId tenantId) {
    // Callable form — returns a value
    String result = TenantContextBridge.withTenant(tenantId, () -> {
        TenantId current = TenantContextBridge.currentTenant();
        return doWork(current);
    });

    // Runnable form — no return value
    TenantContextBridge.withTenant(tenantId, () ->
        record(TenantContextBridge.currentTenant())
    );
    // TenantContextBridge.currentTenant() returns null here — scope is restored on exit
}
```
