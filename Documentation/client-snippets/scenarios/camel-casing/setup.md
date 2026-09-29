```kotlin
import io.cratis.arc.naming.NamingPolicy
import io.cratis.arc.springdata.mongodb.DefaultNamingPolicy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// Users/User.kt
// Kotlin properties are already camelCase, and a document stores each property under its declared
// name, so this read model is persisted with the fields firstName and emailAddress.
data class User(val id: String, val firstName: String, val emailAddress: String)

// Users/ReadModelNaming.kt
// The MongoDB integration registers this policy by itself; declaring it only makes the choice
// visible. It keeps the collection names the Chronicle kernel writes (User -> Users). Chronicle's
// JVM client has no camel-case option, so camel-casing collection names here would make Arc read
// collections that the kernel never writes.
@Configuration
class ReadModelNaming {
    @Bean
    fun namingPolicy(): NamingPolicy = DefaultNamingPolicy()
}
```
