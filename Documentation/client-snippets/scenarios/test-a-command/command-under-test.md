```kotlin
package library.authors

import io.cratis.arc.artifacts.Command
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandValidator
import io.cratis.arc.results.ValidationResult
import org.springframework.stereotype.Component

interface AuthorRegistration {
    suspend fun register(id: AuthorId, name: AuthorName)
}

@Command
data class RecordAuthor(val id: AuthorId, val name: AuthorName) {
    suspend fun handle(registration: AuthorRegistration) {
        registration.register(id, name)
    }
}

@Component
class RecordAuthorValidator : CommandValidator<RecordAuthor> {
    override val commandType: Class<RecordAuthor> = RecordAuthor::class.java

    override suspend fun validate(
        command: RecordAuthor,
        context: CommandContext
    ): List<ValidationResult> =
        if (command.name.value().isBlank()) {
            listOf(ValidationResult.error("An author needs a name.", listOf("name")))
        } else {
            emptyList()
        }
}
```
