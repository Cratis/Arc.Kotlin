```kotlin
import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel
import org.springframework.data.mongodb.repository.MongoRepository
import java.util.UUID

interface AuthorRepository : MongoRepository<Author, UUID>

@ReadModel
data class Author(val id: UUID, val name: String) {
    companion object {
        @JvmStatic
        fun allAuthors(@FromServices authors: AuthorRepository): List<Author> = authors.findAll()
    }
}
```
