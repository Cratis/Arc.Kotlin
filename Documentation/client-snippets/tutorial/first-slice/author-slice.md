```kotlin
@Command
data class RegisterAuthor(val id: AuthorId, val name: AuthorName) {
    suspend fun handle(@FromServices authors: AuthorRepository) {
        authors.save(Author(id, name))
    }
}

@ReadModel
data class Author(val id: AuthorId, val name: AuthorName) {
    companion object {
        @JvmStatic
        fun allAuthors(@FromServices queries: MongoObservableQuery): Flow<List<Author>> =
            queries.observe<Author>()
    }
}
```
