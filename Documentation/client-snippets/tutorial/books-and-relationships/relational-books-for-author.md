```kotlin
@Entity
@Table(name = "books")
class BookRow(
    @field:Id var id: UUID = UUID(0, 0),
    @field:Column(name = "author_id", nullable = false) var authorId: UUID = UUID(0, 0),
    @field:Column(name = "title", nullable = false) var title: String = ""
)

@ReadModel
@AllowAnonymous
data class Book(val id: BookId, val authorId: AuthorId, val title: BookTitle) {
    companion object {
        @JvmStatic
        fun booksForAuthor(authorId: AuthorId, @FromServices queries: JpaObservableQuery): Flow<List<Book>> =
            queries.observe(Book::class.java, JpaSnapshotQuery { em ->
                em.createQuery("select b from BookRow b where b.authorId = :authorId", BookRow::class.java)
                    .setParameter("authorId", authorId.value()).resultList
                    .map { Book(BookId(it.id), AuthorId(it.authorId), BookTitle(it.title)) }
            })
    }
}
```
