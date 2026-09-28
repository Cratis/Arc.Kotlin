```kotlin
@Component
class RegisterAuthorValidator(
    private val authors: AuthorRepository
) : CommandValidator<RegisterAuthor> {
    override val commandType: Class<RegisterAuthor> = RegisterAuthor::class.java

    // validate suspends, so the lookup is awaited directly.
    override suspend fun validate(
        command: RegisterAuthor,
        context: CommandContext
    ): List<ValidationResult> =
        if (authors.existsByName(command.name)) {
            listOf(
                ValidationResult.error(
                    "An author with that name is already registered.",
                    listOf("name")
                )
            )
        } else {
            emptyList()
        }
}
```
