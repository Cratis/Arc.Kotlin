```kotlin
data class BookId(private val rawValue: UUID) : ConceptAs<UUID> {
    override fun value(): UUID = rawValue

    companion object {
        fun new(): BookId = BookId(UUID.randomUUID())
    }
}

data class BookTitle(private val rawValue: String) : ConceptAs<String> {
    override fun value(): String = rawValue
}
```
