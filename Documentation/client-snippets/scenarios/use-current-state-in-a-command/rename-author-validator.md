```kotlin
@Component
class RenameAuthorValidator(
    private val authors: AuthorRepository
) : CommandValidator<RenameAuthor> {
    override val commandType: Class<RenameAuthor> = RenameAuthor::class.java

    override suspend fun validate(
        command: RenameAuthor,
        context: CommandContext
    ): List<ValidationResult> {
        val author = authors.findById(command.id)
            ?: return listOf(ValidationResult.error("Author does not exist.", listOf("id")))

        return if (author.name == command.newName) {
            listOf(ValidationResult.error("Choose a different name.", listOf("newName")))
        } else {
            emptyList()
        }
    }
}
```
