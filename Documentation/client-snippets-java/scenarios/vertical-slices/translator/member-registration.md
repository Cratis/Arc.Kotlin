```java
import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.CommandKey;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.chronicle.events.EventType;
import kotlin.Pair;

// Members/Registration/MemberRegistered.java
/** Records a member's registration in the Library. */
@EventType
public record MemberRegistered(MemberName firstName, MemberName lastName) { }

// Members/Registration/RegisterMember.java
@Command
@AllowAnonymous
public record RegisterMember(@CommandKey MemberId id, MemberName firstName, MemberName lastName) {
    public Pair<MemberId, MemberRegistered> handle() {
        return new Pair<>(id, new MemberRegistered(firstName, lastName));
    }
}
```
