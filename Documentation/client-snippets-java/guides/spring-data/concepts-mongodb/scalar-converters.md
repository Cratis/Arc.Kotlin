```java
@WritingConverter
public class TextWrite implements Converter<TextValue, String> {
    @Override
    public String convert(TextValue source) { return source.value(); }
}

@ReadingConverter
public class TextRead implements Converter<String, TextValue> {
    @Override
    public TextValue convert(String source) { return new TextValue(source); }
}
```
