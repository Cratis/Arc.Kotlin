```kotlin
import io.cratis.chronicle.events.EventType
import io.cratis.chronicle.projections.IProjectionBuilderFor
import io.cratis.chronicle.projections.IProjectionFor

@EventType
data class AuthorImported(val id: String, val firstName: AuthorName, val lastName: AuthorName)

class AuthorProjection : IProjectionFor<Author> {
    override fun define(builder: IProjectionBuilderFor<Author>) {
        builder.from(AuthorImported::class)
    }
}
```
