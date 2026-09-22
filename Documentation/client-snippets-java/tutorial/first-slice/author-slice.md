```java
@Command
public record RegisterAuthor(AuthorId id, AuthorName name) {
    public void handle(@FromServices AuthorRepository authors) {
        authors.save(new Author(id, name));
    }
}

@ReadModel
public record Author(AuthorId id, AuthorName name) {
    public static Flow.Publisher<List<Author>> allAuthors(@FromServices MongoObservableQuery queries) {
        return queries.observePublisher(Author.class);
    }
}
```
