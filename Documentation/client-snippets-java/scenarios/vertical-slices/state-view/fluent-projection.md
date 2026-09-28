```java
import io.cratis.chronicle.events.EventType;
import io.cratis.chronicle.projections.IProjectionBuilderFor;
import io.cratis.chronicle.projections.IProjectionFor;

@EventType
public record AuthorImported(String id, AuthorName firstName, AuthorName lastName) { }

public final class AuthorProjection implements IProjectionFor<Author> {
    @Override
    public void define(IProjectionBuilderFor<Author> builder) {
        builder.from(AuthorImported.class);
    }
}
```
