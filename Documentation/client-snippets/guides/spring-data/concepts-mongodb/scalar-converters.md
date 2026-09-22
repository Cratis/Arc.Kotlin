```kotlin
@WritingConverter
class TextWrite : Converter<TextValue, String> {
    override fun convert(source: TextValue): String = source.value()
}

@ReadingConverter
class TextRead : Converter<String, TextValue> {
    override fun convert(source: String): TextValue = TextValue(source)
}
```
