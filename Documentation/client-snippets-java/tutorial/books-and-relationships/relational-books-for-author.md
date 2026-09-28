```java
@Entity
@Table(name = "books")
class BookRow {
    @Id UUID id;
    @Column(name = "author_id", nullable = false) UUID authorId;
    @Column(name = "title", nullable = false) String title;
    protected BookRow() {}
    BookRow(UUID id, UUID authorId, String title) {
        this.id = id; this.authorId = authorId; this.title = title;
    }
}

@ReadModel
@AllowAnonymous
public record Book(BookId id, AuthorId authorId, BookTitle title) {
    public static Flow.Publisher<List<Book>> booksForAuthor(
        AuthorId authorId, @FromServices JpaObservableQuery queries
    ) {
        return queries.observePublisher(Book.class, em ->
            em.createQuery("select b from BookRow b where b.authorId = :authorId", BookRow.class)
                .setParameter("authorId", authorId.value()).getResultList().stream()
                .map(row -> new Book(new BookId(row.id), new AuthorId(row.authorId), new BookTitle(row.title)))
                .toList());
    }
}
```
