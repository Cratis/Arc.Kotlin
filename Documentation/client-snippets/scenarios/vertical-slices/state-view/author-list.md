```kotlin
import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel as ArcReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.events.EventContext
import io.cratis.chronicle.events.EventType
import io.cratis.chronicle.observation.Reducer
import io.cratis.chronicle.readModels.ReadModel as ChronicleReadModel
import kotlinx.coroutines.flow.Flow

@EventType
data class AuthorRegistered(val firstName: AuthorName, val lastName: AuthorName)

@ArcReadModel
@ChronicleReadModel
@AllowAnonymous
data class Author(
    val id: String = "",
    val firstName: AuthorName = AuthorName(""),
    val lastName: AuthorName = AuthorName("")
) {
    companion object {
        @JvmStatic
        fun allAuthors(@FromServices eventStore: IEventStore): Flow<List<Author>> =
            eventStore.readModels.materialized.observeInstances(Author::class, 0, 50)
    }
}

@Reducer
class AuthorReducer {
    fun registered(event: AuthorRegistered, state: Author?, context: EventContext): Author =
        Author(context.eventSourceId, event.firstName, event.lastName)
}
```
