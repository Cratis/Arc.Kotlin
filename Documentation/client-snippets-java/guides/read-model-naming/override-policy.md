```java
@Configuration
class NamingConfig {
    @Bean
    NamingPolicy namingPolicy() {
        return readModelType -> {
            // The kernel writes to "PersonProjections"; the JVM inflector would produce "People".
            if (readModelType == PersonView.class) return "PersonProjections";
            return new DefaultNamingPolicy().getReadModelName(readModelType);
        };
    }
}
```
