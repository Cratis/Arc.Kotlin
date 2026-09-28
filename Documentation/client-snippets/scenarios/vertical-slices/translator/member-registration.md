```kotlin
import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.chronicle.events.EventType

// Members/Registration/Registration.kt

/** Records a member's registration in the Library. */
@EventType
data class MemberRegistered(val firstName: MemberName, val lastName: MemberName)

@Command
@AllowAnonymous
data class RegisterMember(
    @CommandKey val id: MemberId,
    val firstName: MemberName,
    val lastName: MemberName
) {
    fun handle(): Pair<MemberId, MemberRegistered> = id to MemberRegistered(firstName, lastName)
}
```
