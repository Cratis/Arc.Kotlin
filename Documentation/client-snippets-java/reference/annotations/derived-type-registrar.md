```java
@Bean
DerivedTypeRegistrar externalShapes() {
    return registry -> registry.register(Shape.class, ExternalCircle.class);
}
```
