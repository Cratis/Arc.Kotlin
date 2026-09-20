```kotlin
@Component
class RegisterAuthorValidator : CommandValidator<RegisterAuthor> {
    override val commandType: Class<RegisterAuthor> = RegisterAuthor::class.java

    override suspend fun validate(
        command: RegisterAuthor,
        context: CommandContext
    ): List<ValidationResult> = when {
        command.name.value().isBlank() ->
            listOf(ValidationResult.error("An author needs a name.", listOf("name")))
        command.name.value().length > 200 ->
            listOf(ValidationResult.error("An author name is at most 200 characters.", listOf("name")))
        else -> emptyList()
    }
}
```
