```java
static void resolvesTheTenant(ArcArtifactModule module) throws Exception {
    CommandScenario<CreateTask> configured = new CommandScenario<>(module, CreateTask.class)
        .withTenantResolution(
            new HeaderTenantIdResolver(),
            new TenantResolutionContext(Map.of("X-Cratis-Tenant-Id", "tenant-one")));

    try (BlockingCommandScenario<CreateTask> scenario = new BlockingCommandScenario<>(configured)) {
        scenario.execute(new CreateTask("Try Arc"));
    }
}
```
