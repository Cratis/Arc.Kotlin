```java
import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.CommandKey;
import io.cratis.chronicle.events.EventType;

@Command
public record RegisterAuthor(@CommandKey AuthorId id, String name) {
    public AuthorRegistered handle() { return new AuthorRegistered(name); }
}

@EventType
public record AuthorRegistered(String name) { }
```
