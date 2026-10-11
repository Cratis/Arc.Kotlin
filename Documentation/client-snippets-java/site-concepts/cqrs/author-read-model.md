```java
import io.cratis.arc.artifacts.FromServices;
import io.cratis.arc.artifacts.ReadModel;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;
import java.util.UUID;

interface AuthorRepository extends MongoRepository<Author, UUID> { }

@ReadModel
public record Author(UUID id, String name) {
    public static List<Author> allAuthors(@FromServices AuthorRepository authors) {
        return authors.findAll();
    }
}
```
