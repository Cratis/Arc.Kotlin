```java
@Command
public record AddBook(AuthorId authorId, BookId bookId, BookTitle title) {
    public void handle(@FromServices BookRepository books) {
        books.save(new Book(bookId, authorId, title));
    }
}
```
