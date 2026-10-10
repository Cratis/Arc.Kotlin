```kotlin
import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.chronicle.events.EventType

@Command
data class ReserveBook(@CommandKey val bookId: String, val member: String) {
    fun handle(): BookReserved = BookReserved(member)
}

@EventType
data class BookReserved(val member: String)
```
