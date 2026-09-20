```kotlin
@ReadModel
data class Book(val id: BookId, val authorId: AuthorId, val title: BookTitle) {
    companion object {
        @JvmStatic
        fun booksForAuthor(
            authorId: AuthorId,
            @FromServices queries: MongoObservableQuery
        ): Flow<List<Book>> = queries.observe<Book>(Criteria.where("authorId").`is`(authorId.value()))
    }
}
```
