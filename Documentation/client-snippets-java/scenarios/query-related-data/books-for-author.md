```java
@ReadModel
public record Book(BookId id, AuthorId authorId, BookTitle title) {
    public static Flow.Publisher<List<Book>> booksForAuthor(
        AuthorId authorId,
        @FromServices MongoObservableQuery queries
    ) {
        return MongoObservations.observe(
            queries,
            Book.class,
            Criteria.where("authorId").is(authorId.value()));
    }
}
```
