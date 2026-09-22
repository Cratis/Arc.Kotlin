```kotlin
data class TextValue(private val scalar: String) : ConceptAs<String> {
    init { require(scalar.isNotBlank()) { "Text concept must not be blank." } }
    override fun value(): String = scalar
}

@Converter(autoApply = false)
class TextConverter : AttributeConverter<TextValue, String> {
    override fun convertToDatabaseColumn(attribute: TextValue?): String? = attribute?.value()
    override fun convertToEntityAttribute(dbData: String?): TextValue? = dbData?.let(::TextValue)
}
```
