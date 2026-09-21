```kotlin
@Command
data class RegisterAuthor(val id: AuthorId, val fullName: AuthorName) {   // was name
    suspend fun handle(@FromServices authors: AuthorRepository) {
        authors.save(Author(id, fullName))
    }
}
```
