```java
import io.cratis.arc.chronicle.ChronicleCommandSideEffectHandler;
import io.cratis.arc.results.CommandResult;
import io.cratis.chronicle.events.EventContext;
import io.cratis.chronicle.events.EventType;
import io.cratis.chronicle.observation.OnceOnly;
import io.cratis.chronicle.observation.Reactor;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

// ─── External Event ───────────────────────────────────────────────────────────
// The inbound adapter records this integration event in Chronicle.
// Its string fields mirror the HR payload, not Library domain concepts.

// Members/HRIntegration/HRMemberCreated.java
/** Records the staff-creation payload received from HR. */
@EventType
public record HRMemberCreated(String employeeId, String givenName, String familyName, String status) { }

// ─── Translator Reactor ───────────────────────────────────────────────────────

// Members/HRIntegration/MemberImportReactor.java
@Reactor
public class MemberImportReactor {
    private final ChronicleCommandSideEffectHandler commands;

    public MemberImportReactor(ChronicleCommandSideEffectHandler commands) {
        this.commands = commands;
    }

    @OnceOnly
    public void hrMemberCreated(HRMemberCreated event, EventContext context) {
        // Only import active staff as library members
        if (!"ACTIVE".equals(event.status())) {
            return;
        }

        // The same employee always maps to the same member, so a retried import cannot mint a second one.
        var memberId = new MemberId(UUID.nameUUIDFromBytes(
            ("hr-employee:" + event.employeeId()).getBytes(StandardCharsets.UTF_8)));
        var command = new RegisterMember(
            memberId,
            new MemberName(event.givenName()),
            new MemberName(event.familyName()));
        CommandResult<?> result = commands
            .executeAsync(command, MemberImportReactor.class, context)
            .toCompletableFuture()
            .join();
        if (!result.isSuccess()) {
            throw new MemberImportFailed();
        }
    }
}

// Members/HRIntegration/MemberImportFailed.java
public class MemberImportFailed extends RuntimeException {
    public MemberImportFailed() {
        super("Library member registration failed.");
    }
}
```
