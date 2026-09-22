```java
@Command
@Roles("Librarian")
public record RegisterAuthor(AuthorId id, AuthorName name) {
    public void handle(@FromServices AuthorRepository authors) {
        authors.save(new Author(id, name));
    }
}
```
