```kotlin
package library.authors

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.chronicle.events.EventType

@EventType
data class AuthorRegistered(val name: String)

@Command
data class RegisterAuthor(@CommandKey val authorId: String, val name: String) {
    fun handle(): AuthorRegistered = AuthorRegistered(name)
}
```
