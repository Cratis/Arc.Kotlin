```java
@ReadModel
public record Author(AuthorId id, AuthorName name) {
    public static Flow.Publisher<List<Author>> allAuthors(@FromServices MongoObservableQuery queries) {
        return queries.observePublisher(Author.class);
    }
}
```
