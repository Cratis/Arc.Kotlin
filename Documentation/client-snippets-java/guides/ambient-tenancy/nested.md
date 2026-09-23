```java
void nestedScopes() {
    TenantContextBridge.withTenant(new TenantId("outer"), () -> {
        TenantContextBridge.withTenant(new TenantId("inner"), () ->
            use(TenantContextBridge.currentTenant())  // TenantId("inner")
        );
        use(TenantContextBridge.currentTenant());     // TenantId("outer") — restored
    });
}
```
