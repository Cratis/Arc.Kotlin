```java
import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.FromServices;
import java.util.UUID;

@Command
public record RegisterAuthor(UUID id, String name) {
    public void handle(@FromServices AuthorRepository authors) {
        authors.save(new Author(id, name));
    }
}
```
