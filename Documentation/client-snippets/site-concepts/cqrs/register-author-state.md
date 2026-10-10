```kotlin
import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.FromServices
import java.util.UUID

@Command
data class RegisterAuthor(val id: UUID, val name: String) {
    fun handle(@FromServices authors: AuthorRepository) {
        authors.insert(Author(id, name))
    }
}
```
