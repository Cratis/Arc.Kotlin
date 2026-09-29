```kotlin
import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandValidator
import io.cratis.arc.results.ValidationResult
import io.cratis.chronicle.events.EventType
import org.springframework.stereotype.Component

// Authors/Registration/Registration.kt

/** Records an author's registration with their first and last names. */
@EventType
data class AuthorRegistered(val firstName: AuthorName, val lastName: AuthorName)

@Component
class RegisterAuthorValidator : CommandValidator<RegisterAuthor> {
    override val commandType: Class<RegisterAuthor> = RegisterAuthor::class.java

    override suspend fun validate(command: RegisterAuthor, context: CommandContext): List<ValidationResult> =
        buildList {
            if (command.firstName.value().isBlank()) {
                add(ValidationResult.error("First name is required", listOf("firstName")))
            }
            if (command.lastName.value().isBlank()) {
                add(ValidationResult.error("Last name is required", listOf("lastName")))
            }
        }
}

@Command
@AllowAnonymous
data class RegisterAuthor(
    @CommandKey val id: AuthorId,
    val firstName: AuthorName,
    val lastName: AuthorName
) {
    fun handle(): Pair<AuthorId, AuthorRegistered> = id to AuthorRegistered(firstName, lastName)
}
```
