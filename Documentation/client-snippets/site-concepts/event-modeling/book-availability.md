```kotlin
import io.cratis.chronicle.events.EventType
import io.cratis.chronicle.projections.Decrement
import io.cratis.chronicle.projections.FromEvent
import io.cratis.chronicle.projections.SetFrom
import io.cratis.chronicle.readModels.ReadModel

@EventType
data class BookAdded(val title: String, val copies: Int)

@ReadModel
@FromEvent(BookAdded::class)
@FromEvent(BookReserved::class)
data class BookAvailability(
    val id: String = "",
    val title: String = "",
    @SetFrom("copies", BookAdded::class)
    @Decrement(BookReserved::class)
    val availableCopies: Int = 0
)
```
