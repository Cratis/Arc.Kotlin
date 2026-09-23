```kotlin
suspend fun resolvesTheTenant(module: ArcArtifactModule) {
    val result = CommandScenario(module, CreateTask::class.java)
        .withTenantResolution(
            HeaderTenantIdResolver(),
            TenantResolutionContext(headers = mapOf("X-Cratis-Tenant-Id" to "tenant-one"))
        )
        .execute(CreateTask("Try Arc"))
}
```
