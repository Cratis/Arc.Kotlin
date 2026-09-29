```java
import io.cratis.arc.concepts.ConceptAs;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.validation.ConceptValidator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

// Members/MemberId.java
public record MemberId(UUID value) implements ConceptAs<UUID>, io.cratis.chronicle.concepts.ConceptAs<UUID> {
    public static final MemberId NOT_SET = new MemberId(new UUID(0, 0));

    public static MemberId newId() {
        return new MemberId(UUID.randomUUID());
    }

    @Override
    public UUID getValue() {
        return value;
    }
}

// Members/MemberName.java
public record MemberName(String value) implements ConceptAs<String>, io.cratis.chronicle.concepts.ConceptAs<String> {
    @Override
    public String getValue() {
        return value;
    }
}

// Members/MemberNameValidator.java
@Component
public final class MemberNameValidator implements ConceptValidator<MemberName> {
    @Override
    public Class<MemberName> getConceptType() {
        return MemberName.class;
    }

    @Override
    public List<ValidationResult> validate(MemberName concept) {
        return concept.value().isBlank()
            ? List.of(ValidationResult.error("A name is required."))
            : List.of();
    }
}
```
