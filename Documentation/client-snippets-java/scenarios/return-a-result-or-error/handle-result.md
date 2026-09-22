```java
// An exception implementing ValidationFailure carries validation feedback out of
// the handler; its own message and stack trace are never exposed to the caller.
public static final class AuthorAlreadyRegistered extends RuntimeException implements ValidationFailure {
    public AuthorAlreadyRegistered() {
        super("Duplicate author name");
    }

    @Override
    public List<ValidationResult> getValidationResults() {
        return List.of(ValidationResult.error(
            "An author with that name is already registered.",
            List.of("name")));
    }
}

public AuthorId handle(@FromServices AuthorRepository authors) {
    if (authors.existsByName(name)) throw new AuthorAlreadyRegistered();

    authors.save(new Author(id, name));
    return id;
}
```
