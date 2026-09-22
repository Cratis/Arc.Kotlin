```java
@Command
public record RegisterAuthor(AuthorId id, AuthorName fullName) {   // was name
    public void handle(@FromServices AuthorRepository authors) {
        authors.save(new Author(id, fullName));
    }
}
```
