```java
import io.cratis.arc.artifacts.CommandKey;

@Command
public record RenameAuthor(@CommandKey AuthorId id, AuthorName newName) {
    public void handle(Author author, @FromServices AuthorRepository authors) {
        authors.save(new Author(author.id(), newName));
    }
}
```
