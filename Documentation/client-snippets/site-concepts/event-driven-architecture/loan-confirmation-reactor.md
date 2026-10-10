```kotlin
import io.cratis.chronicle.events.EventContext
import io.cratis.chronicle.events.EventType
import io.cratis.chronicle.observation.Reactor
import java.time.LocalDate

@EventType
data class BookBorrowed(val memberEmail: String, val dueDate: LocalDate)

@Reactor
class LoanConfirmationReactor {
    suspend fun borrowed(event: BookBorrowed, context: EventContext) =
        sendConfirmation(event.memberEmail, event.dueDate)

    private suspend fun sendConfirmation(email: String, dueDate: LocalDate) {}
}
```
