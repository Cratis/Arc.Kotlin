```kotlin
interface AuthorsCatalog {
    suspend fun isRegistered(name: AuthorName): Boolean
}

@Component
class RegisterAuthorValidator(
    private val authors: AuthorsCatalog
) : CommandValidator<RegisterAuthor> {
    override val commandType: Class<RegisterAuthor> = RegisterAuthor::class.java

    // validate is a suspending function, so the lookup is awaited directly -
    // there is no fluent async-predicate rule to reach for.
    override suspend fun validate(
        command: RegisterAuthor,
        context: CommandContext
    ): List<ValidationResult> =
        if (authors.isRegistered(command.name)) {
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
