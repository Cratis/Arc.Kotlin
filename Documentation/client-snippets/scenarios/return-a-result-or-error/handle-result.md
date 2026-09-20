```kotlin
// An exception implementing ValidationFailure carries validation feedback out of
// the handler; its own message and stack trace are never exposed to the caller.
class AuthorAlreadyRegistered : RuntimeException("Duplicate author name"), ValidationFailure {
    override val validationResults: List<ValidationResult> = listOf(
        ValidationResult.error("An author with that name is already registered.", listOf("name"))
    )
}

suspend fun handle(authors: AuthorRepository): AuthorId {
    if (authors.existsByName(name)) throw AuthorAlreadyRegistered()

    authors.save(Author(id, name))
    return id
}
```
