```kotlin
import org.springframework.data.mongodb.core.mapping.Document

@Document
@ReadModel
@AllowAnonymous
data class Author(val id: AuthorId, val name: AuthorName) {
    companion object {
        @JvmStatic
        fun allAuthors(@FromServices queries: MongoObservableQuery): Flow<List<Author>> =
            queries.observe<Author>()
    }
}
```
