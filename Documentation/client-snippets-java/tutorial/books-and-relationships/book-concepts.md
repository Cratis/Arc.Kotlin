```java
// A record component named value satisfies ConceptAs without any extra code.
public record BookId(UUID value) implements ConceptAs<UUID> {
    public static BookId newId() {
        return new BookId(UUID.randomUUID());
    }
}

public record BookTitle(String value) implements ConceptAs<String> {
}
```
