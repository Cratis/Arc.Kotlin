```kotlin
@Component
class CreateTaskValidator : CommandValidator<CreateTask> {
    override val commandType = CreateTask::class.java

    override suspend fun validate(command: CreateTask, context: CommandContext): List<ValidationResult> =
        if (command.title.isBlank()) {
            listOf(ValidationResult.error("A task title is required.", listOf("title")))
        } else {
            emptyList()
        }
}
```
