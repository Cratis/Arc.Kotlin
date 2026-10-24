```java
import io.cratis.fundamentals.concepts.ConceptAs;
import java.util.UUID;

public record AuthorId(UUID value) implements ConceptAs<UUID>, io.cratis.chronicle.concepts.ConceptAs<UUID> {
    @Override
    public UUID getValue() { return value; }
}
```
