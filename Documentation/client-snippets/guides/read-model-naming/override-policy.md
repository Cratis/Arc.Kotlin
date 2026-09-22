```kotlin
@Configuration
class NamingConfig {
    @Bean
    fun namingPolicy(): NamingPolicy = object : NamingPolicy {
        override fun getReadModelName(readModelType: Class<*>): String =
            when (readModelType) {
                // The kernel writes to "PersonProjections"; the JVM inflector would produce "People".
                PersonView::class.java -> "PersonProjections"
                else -> DefaultNamingPolicy().getReadModelName(readModelType)
            }
    }
}
```
