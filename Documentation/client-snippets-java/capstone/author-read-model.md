```java
import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.chronicle.events.EventContext;
import io.cratis.chronicle.java.ReadModelsJavaBridge;
import io.cratis.chronicle.observation.Reducer;
import io.cratis.chronicle.IEventStore;
import java.util.List;

@io.cratis.arc.artifacts.ReadModel
@io.cratis.chronicle.readModels.ReadModel
@AllowAnonymous
public record Author(String id, String name) {
    public static List<Author> allAuthors(@FromServices IEventStore store) {
        // A snapshot: live updates wait on https://github.com/Cratis/Chronicle/issues/4365
        return ReadModelsJavaBridge.getInstances(store.getReadModels(), Author.class);
    }
}

@Reducer
public final class AuthorReducer {
    public Author registered(AuthorRegistered event, Author state, EventContext context) {
        return new Author(context.getEventSourceId(), event.name());
    }
}
```
