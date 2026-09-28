```kotlin
import io.cratis.arc.artifacts.CommandKey

@Command
@AllowAnonymous
data class RenameAuthor(@CommandKey val id: AuthorId, val newName: AuthorName) {
    suspend fun handle(author: Author, @FromServices authors: AuthorRepository) {
        authors.save(author.copy(name = newName))
    }
}
```
