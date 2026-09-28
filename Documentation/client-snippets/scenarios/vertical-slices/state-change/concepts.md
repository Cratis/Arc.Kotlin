```kotlin
import io.cratis.arc.concepts.ConceptAs as ArcConceptAs
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ConceptValidator
import io.cratis.chronicle.concepts.ConceptAs as ChronicleConceptAs
import java.util.UUID
import org.springframework.stereotype.Component

// Authors/AuthorId.kt
data class AuthorId(private val id: UUID) : ArcConceptAs<UUID>, ChronicleConceptAs<UUID> {
    override fun value(): UUID = id
    override val value: UUID get() = id

    companion object {
        val NOT_SET = AuthorId(UUID(0, 0))
        fun new(): AuthorId = AuthorId(UUID.randomUUID())
    }
}

// Authors/AuthorName.kt
data class AuthorName(private val name: String) : ArcConceptAs<String>, ChronicleConceptAs<String> {
    override fun value(): String = name
    override val value: String get() = name
}

@Component
class AuthorNameValidator : ConceptValidator<AuthorName> {
    override val conceptType: Class<AuthorName> = AuthorName::class.java

    override fun validate(concept: AuthorName): List<ValidationResult> =
        if (concept.value().isBlank()) listOf(ValidationResult.error("A name is required.")) else emptyList()
}
```
