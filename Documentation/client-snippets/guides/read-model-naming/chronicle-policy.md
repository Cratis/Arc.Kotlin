```kotlin
@Configuration
class ChronicleNamingConfig {
    // Names only the collections Chronicle projects into; Arc keeps reading through its own NamingPolicy.
    @Bean
    fun readModelNamingPolicy(): ReadModelNamingPolicy =
        ReadModelNamingPolicy { readModelClass -> "projected_${readModelClass.simpleName}" }
}
```
