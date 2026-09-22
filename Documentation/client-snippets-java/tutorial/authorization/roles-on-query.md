```java
@ReadModel
public record Author(AuthorId id, AuthorName name) {
    @Roles("Librarian")
    public static Flow.Publisher<List<Author>> allAuthors(@FromServices MongoObservableQuery queries) {
        return queries.observePublisher(Author.class);
    }
}
```
