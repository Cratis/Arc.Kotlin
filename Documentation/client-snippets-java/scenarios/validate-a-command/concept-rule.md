```java
// The concept rule is synchronous; its model-pipeline bridge is inherited.
// Java publishes it as an ordinary component.
@Component
public final class AuthorNameValidator implements FundamentalsConceptValidator<AuthorName> {
    @Override
    public Class<AuthorName> getConceptType() {
        return AuthorName.class;
    }

    @Override
    public List<ValidationResult> validate(AuthorName concept) {
        return concept.value().isBlank()
            ? List.of(ValidationResult.error("An author needs a name."))
            : List.of();
    }
}
```
