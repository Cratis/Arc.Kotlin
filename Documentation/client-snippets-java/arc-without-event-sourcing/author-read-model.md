```java
import org.springframework.data.mongodb.core.mapping.Document;

@Document
@ReadModel
@AllowAnonymous
public record Author(AuthorId id, AuthorName name) {
    public static Flow.Publisher<List<Author>> allAuthors(@FromServices MongoObservableQuery queries) {
        return queries.observePublisher(Author.class);
    }
}
```
