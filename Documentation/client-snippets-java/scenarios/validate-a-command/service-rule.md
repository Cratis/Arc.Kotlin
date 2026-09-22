```java
public interface AuthorsCatalog {
    CompletionStage<Boolean> isRegistered(AuthorName name);
}

// AsyncCommandValidator returns a CompletionStage, so the lookup composes without
// blocking - there is no fluent async-predicate rule to reach for.
public final class RegisterAuthorValidator implements AsyncCommandValidator<RegisterAuthor> {
    private final AuthorsCatalog authors;

    public RegisterAuthorValidator(AuthorsCatalog authors) {
        this.authors = authors;
    }

    @Override
    public Class<RegisterAuthor> getCommandType() {
        return RegisterAuthor.class;
    }

    @Override
    public CompletionStage<List<ValidationResult>> validate(
        RegisterAuthor command,
        CommandContext context
    ) {
        return authors.isRegistered(command.name()).thenApply(registered -> registered
            ? List.of(ValidationResult.error(
                "An author with that name is already registered.",
                List.of("name")))
            : List.of());
    }
}

@Configuration
public class RegisterAuthorValidation {
    @Bean
    public CommandValidator<RegisterAuthor> registerAuthorValidator(AuthorsCatalog authors) {
        return new AsyncCommandValidatorAdapter<>(new RegisterAuthorValidator(authors));
    }
}
```
