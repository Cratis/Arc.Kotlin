```java
@Configuration
public class ChronicleNamingConfig {
    // Names only the collections Chronicle projects into; Arc keeps reading through its own NamingPolicy.
    @Bean
    public ReadModelNamingPolicy readModelNamingPolicy() {
        return readModelClass -> "projected_" + readModelClass.getSimpleName();
    }
}
```
