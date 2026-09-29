```kotlin
import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel as ArcReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.events.EventContext
import io.cratis.chronicle.observation.Reducer
import io.cratis.chronicle.readModels.ReadModel as ChronicleReadModel

@ArcReadModel
@ChronicleReadModel
@AllowAnonymous
data class Author(val id: String = "", val name: String = "") {
    companion object {
        // A snapshot: live updates wait on https://github.com/Cratis/Chronicle/issues/4365
        @JvmStatic
        suspend fun allAuthors(@FromServices eventStore: IEventStore): List<Author> =
            eventStore.readModels.getInstances(Author::class)
    }
}

@Reducer
class AuthorReducer {
    fun registered(event: AuthorRegistered, state: Author?, context: EventContext): Author =
        Author(id = context.eventSourceId, name = event.name)
}
```
