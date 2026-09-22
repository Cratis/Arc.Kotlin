```java
@Roles("Librarian")                         // only a Librarian may register an author
@Command
public record RegisterAuthor(AuthorId id, AuthorName name) {
    public void handle(@FromServices AuthorRepository authors) {
        authors.save(new Author(id, name));
    }
}

@ReadModel
public record Author(AuthorId id, AuthorName name) {
    @AllowAnonymous                         // the public catalog is open to everyone
    public static Flow.Publisher<List<Author>> allAuthors(@FromServices AuthorRepository authors) {
        return authors.observeAll();
    }
}
```
