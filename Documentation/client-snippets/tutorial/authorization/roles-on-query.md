```kotlin
@ReadModel
data class Author(val id: AuthorId, val name: AuthorName) {
    companion object {
        @JvmStatic
        @Roles("Librarian")
        fun allAuthors(@FromServices queries: MongoObservableQuery): Flow<List<Author>> =
            queries.observe<Author>()
    }
}
```
