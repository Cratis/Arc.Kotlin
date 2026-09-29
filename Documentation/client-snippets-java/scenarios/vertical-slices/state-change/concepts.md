```java
import io.cratis.arc.concepts.ConceptAs;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.validation.ConceptValidator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

// Authors/AuthorId.java
public record AuthorId(UUID value) implements ConceptAs<UUID>, io.cratis.chronicle.concepts.ConceptAs<UUID> {
    public static final AuthorId NOT_SET = new AuthorId(new UUID(0, 0));

    public static AuthorId newId() {
        return new AuthorId(UUID.randomUUID());
    }

    @Override
    public UUID getValue() {
        return value;
    }
}

// Authors/AuthorName.java
public record AuthorName(String value) implements ConceptAs<String>, io.cratis.chronicle.concepts.ConceptAs<String> {
    @Override
    public String getValue() {
        return value;
    }
}

// Authors/AuthorNameValidator.java
@Component
public final class AuthorNameValidator implements ConceptValidator<AuthorName> {
    @Override
    public Class<AuthorName> getConceptType() {
        return AuthorName.class;
    }

    @Override
    public List<ValidationResult> validate(AuthorName concept) {
        return concept.value().isBlank()
            ? List.of(ValidationResult.error("A name is required."))
            : List.of();
    }
}
```
