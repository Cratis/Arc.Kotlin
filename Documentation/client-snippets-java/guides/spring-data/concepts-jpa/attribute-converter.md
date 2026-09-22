```java
public record TextValue(String value) implements ConceptAs<String> {
    public TextValue {
        Objects.requireNonNull(value);
        if (value.isBlank()) throw new IllegalArgumentException("Text concept must not be blank.");
    }
}

@Converter(autoApply = false)
public class TextConverter implements AttributeConverter<TextValue, String> {
    @Override
    public String convertToDatabaseColumn(TextValue attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public TextValue convertToEntityAttribute(String dbData) {
        return dbData == null ? null : new TextValue(dbData);
    }
}
```
