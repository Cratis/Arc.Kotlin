```kotlin
fun mapperFor(module: ArcArtifactModule): ObjectMapper {
    val registry = ConcurrentDerivedTypeRegistry()
    ArcArtifactModuleRegistry.registerDerivedTypes(module, registry)
    return ArcObjectMapper.create(registry)
}
```
