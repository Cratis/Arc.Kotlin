```java
import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.chronicle.events.EventContext;
import io.cratis.chronicle.events.EventType;
import io.cratis.chronicle.observation.Reducer;
import io.cratis.chronicle.spring.Chronicle;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@EventType
public record AuthorRegistered(AuthorName firstName, AuthorName lastName) { }

@io.cratis.arc.artifacts.ReadModel
@io.cratis.chronicle.readModels.ReadModel
@AllowAnonymous
public record Author(String id, AuthorName firstName, AuthorName lastName) {
    public static CompletionStage<List<Author>> allAuthors(@FromServices Chronicle chronicle) {
        return CompletableFuture.completedFuture(chronicle.readModels(Author.class));
    }
}

@Reducer
public final class AuthorReducer {
    public Author registered(AuthorRegistered event, Author state, EventContext context) {
        return new Author(context.getEventSourceId(), event.firstName(), event.lastName());
    }
}
```
