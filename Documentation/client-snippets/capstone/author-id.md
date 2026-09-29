```kotlin
import io.cratis.arc.concepts.ConceptAs as ArcConceptAs
import io.cratis.chronicle.concepts.ConceptAs as ChronicleConceptAs
import java.util.UUID

data class AuthorId(private val id: UUID) : ArcConceptAs<UUID>, ChronicleConceptAs<UUID> {
    override fun value(): UUID = id
    override val value: UUID get() = id

    companion object {
        fun new(): AuthorId = AuthorId(UUID.randomUUID())
    }
}
```
