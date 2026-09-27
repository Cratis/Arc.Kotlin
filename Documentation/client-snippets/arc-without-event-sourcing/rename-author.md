```kotlin
import io.cratis.arc.artifacts.CommandKey

@Command
data class RenameAuthor(@CommandKey val id: AuthorId, val newName: AuthorName) {
    suspend fun handle(author: Author, @FromServices authors: AuthorRepository) {
        authors.save(author.copy(name = newName))
    }
}
```
