```java
import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.CommandKey;
import io.cratis.chronicle.events.EventType;

@Command
public record ReserveBook(@CommandKey String bookId, String member) {
    public BookReserved handle() {
        return new BookReserved(member);
    }
}

@EventType
public record BookReserved(String member) { }
```
