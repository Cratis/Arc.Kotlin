```java
import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.CommandKey;
import io.cratis.chronicle.events.EventType;

@EventType
public record AuthorRegistered(String name) { }

@Command
public record RegisterAuthor(@CommandKey String authorId, String name) {
    public AuthorRegistered handle() {
        return new AuthorRegistered(name);
    }
}
```
