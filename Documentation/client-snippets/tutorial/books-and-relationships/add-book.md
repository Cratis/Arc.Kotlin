```kotlin
@Command
data class AddBook(val authorId: AuthorId, val bookId: BookId, val title: BookTitle) {
    suspend fun handle(@FromServices books: BookRepository) {
        books.save(Book(bookId, authorId, title))
    }
}
```
