```java
static ObjectMapper mapperFor(ArcArtifactModule module) {
    var registry = new ConcurrentDerivedTypeRegistry();
    ArcArtifactModuleRegistry.registerDerivedTypes(module, registry);
    return ArcObjectMapper.create(registry);
}
```
