```kotlin
import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.chronicle.ChronicleCommandSideEffectHandler
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.events.EventContext
import io.cratis.chronicle.events.EventType
import io.cratis.chronicle.observation.OnceOnly
import io.cratis.chronicle.observation.Reactor
import io.cratis.chronicle.observation.Reducer
import io.cratis.chronicle.projections.FromEvent
import io.cratis.chronicle.projections.RemovedWith
import io.cratis.chronicle.readModels.Passive
import io.cratis.chronicle.readModels.ReadModel
import java.time.Instant
import java.util.UUID

// Reservations/ExpiryManagement/ExpiryManagement.kt

// ─── Read Models ──────────────────────────────────────────────────────────────

// A reducer, not @FromEvent: it copies the event-source ID into the model.
@ReadModel
data class ReservationDueForExpiry(
    val id: String = "",
    val expiresAt: Instant = Instant.EPOCH,
    val open: Boolean = false
)

@Reducer
class ReservationDueForExpiryReducer {
    fun reserved(event: BookReserved, state: ReservationDueForExpiry?, context: EventContext) =
        ReservationDueForExpiry(context.eventSourceId, event.expiresAt, open = true)

    fun collected(event: BookBorrowedFromReservation, state: ReservationDueForExpiry?) = state?.copy(open = false)

    fun cancelled(event: ReservationCancelled, state: ReservationDueForExpiry?) = state?.copy(open = false)

    fun expired(event: ReservationExpired, state: ReservationDueForExpiry?) = state?.copy(open = false)
}

@ReadModel
@Passive
@FromEvent(BookReserved::class)
@RemovedWith(BookBorrowedFromReservation::class)
@RemovedWith(ReservationCancelled::class)
@RemovedWith(ReservationExpired::class)
data class PendingReservation(
    val isbn: ISBN = ISBN.NOT_SET,
    val memberId: MemberId = MemberId.NOT_SET,
    val expiresAt: Instant = Instant.EPOCH
)

// ─── Events ───────────────────────────────────────────────────────────────────

/** Records the expiry of a reservation that was not collected in time. */
@EventType
data class ReservationExpired(val isbn: ISBN, val memberId: MemberId)

/** Records the scheduler's daily opportunity to check overdue reservations. */
@EventType
data class DailyTick(val occurredAt: Instant)

// ─── Command ──────────────────────────────────────────────────────────────────

@Command
@AllowAnonymous
data class CancelExpiredReservation(@CommandKey val reservationId: ReservationId) {
    fun provide(): Instant = Instant.now()

    fun handle(now: Instant, reservation: PendingReservation?): ReservationExpired? {
        if (reservation == null || reservation.expiresAt.isAfter(now)) {
            return null
        }

        return ReservationExpired(reservation.isbn, reservation.memberId)
    }
}

// ─── Reactor ──────────────────────────────────────────────────────────────────

@Reactor
class ReservationExpiryReactor(
    private val eventStore: IEventStore,
    private val commands: ChronicleCommandSideEffectHandler
) {
    @OnceOnly
    suspend fun dailyTick(event: DailyTick, context: EventContext) {
        val expired = eventStore.readModels.getInstances(ReservationDueForExpiry::class)
            .filter { reservation -> reservation.open && !reservation.expiresAt.isAfter(event.occurredAt) }

        for (reservation in expired) {
            val reservationId = ReservationId(UUID.fromString(reservation.id))
            val result = commands.execute(
                CancelExpiredReservation(reservationId),
                ReservationExpiryReactor::class.java,
                context
            )
            if (!result.isSuccess) {
                throw ReservationExpiryFailed(reservationId)
            }
        }
    }
}

class ReservationExpiryFailed(reservationId: ReservationId) :
    Exception("Could not expire reservation '${reservationId.value()}'.")
```
