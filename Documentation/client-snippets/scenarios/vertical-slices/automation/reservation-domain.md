```kotlin
import io.cratis.arc.concepts.ConceptAs as ArcConceptAs
import io.cratis.chronicle.concepts.ConceptAs as ChronicleConceptAs
import io.cratis.chronicle.events.EventType
import java.util.UUID

// Reservations/ReservationId.kt
data class ReservationId(private val id: UUID) : ArcConceptAs<UUID>, ChronicleConceptAs<UUID> {
    override fun value(): UUID = id
    override val value: UUID get() = id

    companion object {
        val NOT_SET = ReservationId(UUID(0, 0))
        fun new(): ReservationId = ReservationId(UUID.randomUUID())
    }
}

// Reservations/ISBN.kt
data class ISBN(private val isbn: String) : ArcConceptAs<String>, ChronicleConceptAs<String> {
    override fun value(): String = isbn
    override val value: String get() = isbn

    companion object {
        val NOT_SET = ISBN("")
    }
}

// Reservations/ReservationEvents.kt

/** Records a book held for a member until the collection deadline. */
// Deadlines are epoch milliseconds: the JVM Chronicle client cannot append java.time values yet.
@EventType
data class BookReserved(val isbn: ISBN, val memberId: MemberId, val expiresAt: Long)

/** Records that a reservation was canceled without collection. */
@EventType
data class ReservationCancelled(val isbn: ISBN, val memberId: MemberId)

/** Records that the member collected the reserved book. */
@EventType
data class BookBorrowedFromReservation(val isbn: ISBN, val memberId: MemberId)
```
