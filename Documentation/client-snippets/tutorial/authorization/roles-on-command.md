```kotlin
@Command
@Roles("Librarian")
data class RegisterAuthor(val id: AuthorId, val name: AuthorName) {
    suspend fun handle(@FromServices authors: AuthorRepository) {
        authors.save(Author(id, name))
    }
}
```
