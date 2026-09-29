```java
import io.cratis.arc.concepts.ConceptAs;
import io.cratis.chronicle.events.EventType;
import java.time.Instant;
import java.util.UUID;

// Reservations/ReservationId.java
public record ReservationId(UUID value)
    implements ConceptAs<UUID>, io.cratis.chronicle.concepts.ConceptAs<UUID> {
    public static final ReservationId NOT_SET = new ReservationId(new UUID(0, 0));

    public static ReservationId newId() {
        return new ReservationId(UUID.randomUUID());
    }

    @Override
    public UUID getValue() {
        return value;
    }
}

// Reservations/ISBN.java
public record ISBN(String value) implements ConceptAs<String>, io.cratis.chronicle.concepts.ConceptAs<String> {
    public static final ISBN NOT_SET = new ISBN("");

    @Override
    public String getValue() {
        return value;
    }
}

// Reservations/BookReserved.java
/** Records a book held for a member until the collection deadline. */
@EventType
public record BookReserved(ISBN isbn, MemberId memberId, Instant expiresAt) { }

// Reservations/ReservationCancelled.java
/** Records that a reservation was canceled without collection. */
@EventType
public record ReservationCancelled(ISBN isbn, MemberId memberId) { }

// Reservations/BookBorrowedFromReservation.java
/** Records that the member collected the reserved book. */
@EventType
public record BookBorrowedFromReservation(ISBN isbn, MemberId memberId) { }
```
