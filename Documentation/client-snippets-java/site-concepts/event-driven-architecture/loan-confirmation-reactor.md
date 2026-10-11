```java
import io.cratis.chronicle.events.EventContext;
import io.cratis.chronicle.events.EventType;
import io.cratis.chronicle.observation.Reactor;
import java.time.LocalDate;

@EventType
public record BookBorrowed(String memberEmail, LocalDate dueDate) { }

@Reactor
public class LoanConfirmationReactor {
    public void borrowed(BookBorrowed event, EventContext context) {
        sendConfirmation(event.memberEmail(), event.dueDate());
    }

    private void sendConfirmation(String email, LocalDate dueDate) { }
}
```
