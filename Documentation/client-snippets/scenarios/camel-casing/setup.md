```kotlin
// Users/User.kt
// Kotlin properties are already camelCase, and a document stores each property under its declared
// name, so this read model is persisted with the fields firstName and emailAddress.
// Chronicle's JVM client has no camel-case option; field names need no configuration.
//
// Collection names need none either. With Arc's MongoDB integration and the Chronicle starter both
// on the classpath, Arc's NamingPolicy also names the collection Chronicle projects this read model
// into (Users), which is the collection Arc reads. To choose other names, declare your own Arc
// NamingPolicy bean, or a Chronicle ReadModelNamingPolicy bean to name Chronicle's collections alone.
data class User(val id: String, val firstName: String, val emailAddress: String)
```
