```kotlin
@Embeddable
data class UuidId(var scalar: UUID = UUID(0, 0)) : ConceptAs<UUID>, Serializable {
    override fun value(): UUID = scalar
}

@Entity
@ReadModel
open class UuidRow(
    @field:EmbeddedId
    @field:AttributeOverride(name = "scalar", column = Column(name = "concept_id"))
    open var id: UuidId = UuidId()
) {
    @field:Convert(converter = TextConverter::class)
    @field:Column(name = "text_field")
    open var textField: TextValue? = null
}
```
