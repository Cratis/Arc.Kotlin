```java
import io.cratis.arc.concepts.ConceptAs;

public record AuthorId(String value) implements ConceptAs<String>, io.cratis.chronicle.concepts.ConceptAs<String> {
    @Override
    public String getValue() { return value; }
}
```
