```kotlin
@Roles("Librarian")                         // only a Librarian may register an author
@Command
class RegisterAuthor(val id: AuthorId, val name: AuthorName) {
    suspend fun handle(@FromServices authors: AuthorRepository) {
        authors.save(Author(id, name))
    }
}

@ReadModel
data class Author(val id: AuthorId, val name: AuthorName) {
    companion object {
        @JvmStatic
        @AllowAnonymous                     // the public catalog is open to everyone
        fun allAuthors(@FromServices authors: AuthorRepository): Flow<List<Author>> =
            authors.observeAll()
    }
}
```
