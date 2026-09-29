```kotlin
import io.cratis.arc.concepts.ConceptAs as ArcConceptAs
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ConceptValidator
import io.cratis.chronicle.concepts.ConceptAs as ChronicleConceptAs
import java.util.UUID
import org.springframework.stereotype.Component

// Members/MemberId.kt
data class MemberId(private val id: UUID) : ArcConceptAs<UUID>, ChronicleConceptAs<UUID> {
    override fun value(): UUID = id
    override val value: UUID get() = id

    companion object {
        val NOT_SET = MemberId(UUID(0, 0))
        fun new(): MemberId = MemberId(UUID.randomUUID())
    }
}

// Members/MemberName.kt
data class MemberName(private val name: String) : ArcConceptAs<String>, ChronicleConceptAs<String> {
    override fun value(): String = name
    override val value: String get() = name
}

@Component
class MemberNameValidator : ConceptValidator<MemberName> {
    override val conceptType: Class<MemberName> = MemberName::class.java

    override fun validate(concept: MemberName): List<ValidationResult> =
        if (concept.value().isBlank()) listOf(ValidationResult.error("A name is required.")) else emptyList()
}
```
