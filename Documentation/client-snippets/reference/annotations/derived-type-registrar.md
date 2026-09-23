```kotlin
@Bean
fun externalShapes() = DerivedTypeRegistrar { registry ->
    registry.register(Shape::class.java, ExternalCircle::class.java)
}
```
