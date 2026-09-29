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
// Chronicle's JVM client names the collection a projection writes after the read model's
// identifier: the class simple name, unpluralized (User), or the id of an explicit
// @ReadModel(id = ...). Arc's default policy pluralizes (Users), so turn pluralizing off to read the
// collection the kernel writes. An explicit @ReadModel id changes the kernel's collection name, and
// then this policy, which uses the class simple name, must be overridden for that type.
// Chronicle's JVM client has no camel-case option; field names need no configuration.
@Configuration
class ReadModelNaming {
    @Bean
    fun namingPolicy(): NamingPolicy = DefaultNamingPolicy(pluralizeReadModelNames = false)
}
```
