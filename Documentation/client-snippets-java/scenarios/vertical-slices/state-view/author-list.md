```java
import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.chronicle.IEventStore;
import io.cratis.chronicle.events.EventContext;
import io.cratis.chronicle.events.EventType;
import io.cratis.chronicle.java.ReadModelsJavaBridge;
import io.cratis.chronicle.observation.Reducer;
import java.util.List;
import java.util.concurrent.Flow;

@EventType
public record AuthorRegistered(AuthorName firstName, AuthorName lastName) { }

@io.cratis.arc.artifacts.ReadModel
@io.cratis.chronicle.readModels.ReadModel
@AllowAnonymous
public record Author(String id, AuthorName firstName, AuthorName lastName) {
    public static Flow.Publisher<List<Author>> allAuthors(@FromServices IEventStore store) {
        return ReadModelsJavaBridge.observeMaterializedInstancesPublisher(store.getReadModels(), Author.class, 0, 50);
    }
}

@Reducer
public final class AuthorReducer {
    public Author registered(AuthorRegistered event, Author state, EventContext context) {
        return new Author(context.getEventSourceId(), event.firstName(), event.lastName());
    }
}
```
