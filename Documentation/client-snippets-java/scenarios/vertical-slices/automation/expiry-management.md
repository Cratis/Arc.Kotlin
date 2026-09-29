```java
import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.CommandKey;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.arc.chronicle.ChronicleCommandSideEffectHandler;
import io.cratis.arc.results.CommandResult;
import io.cratis.chronicle.events.EventContext;
import io.cratis.chronicle.events.EventType;
import io.cratis.chronicle.observation.OnceOnly;
import io.cratis.chronicle.observation.Reactor;
import io.cratis.chronicle.projections.FromEvent;
import io.cratis.chronicle.projections.FromEventSourceId;
import io.cratis.chronicle.projections.RemovedWith;
import io.cratis.chronicle.readModels.Passive;
import io.cratis.chronicle.readModels.ReadModel;
import io.cratis.chronicle.spring.Chronicle;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

// ─── Read Models ──────────────────────────────────────────────────────────────

// Reservations/ExpiryManagement/ReservationDueForExpiry.java
@ReadModel
@FromEvent(eventType = BookReserved.class)
@RemovedWith(eventType = BookBorrowedFromReservation.class)
@RemovedWith(eventType = ReservationCancelled.class)
@RemovedWith(eventType = ReservationExpired.class)
public class ReservationDueForExpiry {
    @FromEventSourceId
    public String id = "";
    public Instant expiresAt = Instant.EPOCH;
}

// Reservations/ExpiryManagement/PendingReservation.java
@ReadModel
@Passive
@FromEvent(eventType = BookReserved.class)
@RemovedWith(eventType = BookBorrowedFromReservation.class)
@RemovedWith(eventType = ReservationCancelled.class)
@RemovedWith(eventType = ReservationExpired.class)
public class PendingReservation {
    public ISBN isbn = ISBN.NOT_SET;
    public MemberId memberId = MemberId.NOT_SET;
    public Instant expiresAt = Instant.EPOCH;
}

// ─── Events ───────────────────────────────────────────────────────────────────

// Reservations/ExpiryManagement/ReservationExpired.java
/** Records the expiry of a reservation that was not collected in time. */
@EventType
public record ReservationExpired(ISBN isbn, MemberId memberId) { }

// Reservations/ExpiryManagement/DailyTick.java
/** Records the scheduler's daily opportunity to check overdue reservations. */
@EventType
public record DailyTick(Instant occurredAt) { }

// ─── Command ──────────────────────────────────────────────────────────────────

// Reservations/ExpiryManagement/CancelExpiredReservation.java
@Command
@AllowAnonymous
public record CancelExpiredReservation(@CommandKey ReservationId reservationId) {
    public Instant provide() {
        return Instant.now();
    }

    public ReservationExpired handle(Instant now, Optional<PendingReservation> reservation) {
        if (reservation.isEmpty() || reservation.get().expiresAt.isAfter(now)) {
            return null;
        }

        return new ReservationExpired(reservation.get().isbn, reservation.get().memberId);
    }
}

// ─── Reactor ──────────────────────────────────────────────────────────────────

// Reservations/ExpiryManagement/ReservationExpiryReactor.java
@Reactor
public class ReservationExpiryReactor {
    private final Chronicle chronicle;
    private final ChronicleCommandSideEffectHandler commands;

    public ReservationExpiryReactor(Chronicle chronicle, ChronicleCommandSideEffectHandler commands) {
        this.chronicle = chronicle;
        this.commands = commands;
    }

    @OnceOnly
    public void dailyTick(DailyTick event, EventContext context) {
        var expired = chronicle.readModels(ReservationDueForExpiry.class).stream()
            .filter(reservation -> !reservation.expiresAt.isAfter(event.occurredAt()))
            .toList();

        for (var reservation : expired) {
            var reservationId = new ReservationId(UUID.fromString(reservation.id));
            CommandResult<?> result = commands
                .executeAsync(new CancelExpiredReservation(reservationId), ReservationExpiryReactor.class, context)
                .toCompletableFuture()
                .join();
            if (!result.isSuccess()) {
                throw new ReservationExpiryFailed(reservationId);
            }
        }
    }
}

// Reservations/ExpiryManagement/ReservationExpiryFailed.java
public class ReservationExpiryFailed extends RuntimeException {
    public ReservationExpiryFailed(ReservationId reservationId) {
        super("Could not expire reservation '" + reservationId.value() + "'.");
    }
}
```
