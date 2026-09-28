```kotlin
import io.cratis.arc.chronicle.ChronicleCommandSideEffectHandler
import io.cratis.chronicle.events.EventContext
import io.cratis.chronicle.events.EventType
import io.cratis.chronicle.observation.OnceOnly
import io.cratis.chronicle.observation.Reactor

// Members/HRIntegration/HRIntegration.kt

// ─── External Event ───────────────────────────────────────────────────────────
// The inbound adapter records this integration event in Chronicle.
// Its string fields mirror the HR payload, not Library domain concepts.

/** Records the staff-creation payload received from HR. */
@EventType
data class HRMemberCreated(
    val employeeId: String,
    val givenName: String,
    val familyName: String,
    val status: String
)

// ─── Translator Reactor ───────────────────────────────────────────────────────

@Reactor
class MemberImportReactor(private val commands: ChronicleCommandSideEffectHandler) {
    @OnceOnly
    suspend fun hrMemberCreated(event: HRMemberCreated, context: EventContext) {
        // Only import active staff as library members
        if (event.status != "ACTIVE") {
            return
        }

        val result = commands.execute(
            RegisterMember(MemberId.new(), MemberName(event.givenName), MemberName(event.familyName)),
            MemberImportReactor::class.java,
            context
        )
        if (!result.isSuccess) {
            throw MemberImportFailed()
        }
    }
}

class MemberImportFailed : Exception("Library member registration failed.")
```
