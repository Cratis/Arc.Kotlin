```kotlin
import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.chronicle.events.EventType

@Command
data class RegisterAuthor(@CommandKey val id: AuthorId, val name: String) {
    fun handle(): AuthorRegistered = AuthorRegistered(name)
}

@EventType
data class AuthorRegistered(val name: String = "")
```
