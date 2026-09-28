```kotlin
import io.cratis.arc.concepts.ConceptAs as ArcConceptAs
import io.cratis.chronicle.concepts.ConceptAs as ChronicleConceptAs
import java.util.UUID

data class AuthorId(private val id: String) : ArcConceptAs<String>, ChronicleConceptAs<String> {
    override fun value(): String = id
    override val value: String get() = id

    companion object {
        fun new(): AuthorId = AuthorId(UUID.randomUUID().toString())
    }
}
```
