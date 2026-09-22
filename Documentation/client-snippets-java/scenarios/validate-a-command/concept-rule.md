```java
// ConceptValidator has no coroutine in its signature, so Java implements it directly
// and publishes it as an ordinary component.
@Component
public final class AuthorNameValidator implements ConceptValidator<AuthorName> {
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
