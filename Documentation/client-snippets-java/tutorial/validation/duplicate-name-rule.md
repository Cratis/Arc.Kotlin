```java
public final class RegisterAuthorValidator implements BlockingCommandValidator<RegisterAuthor> {
    private final AuthorRepository authors;

    public RegisterAuthorValidator(AuthorRepository authors) {
        this.authors = authors;
    }

    @Override
    public Class<RegisterAuthor> getCommandType() {
        return RegisterAuthor.class;
    }

    // validate has no coroutine in its signature, so the blocking lookup is called
    // directly. Reach for AsyncCommandValidator when the lookup itself is asynchronous.
    @Override
    public List<ValidationResult> validate(RegisterAuthor command, CommandContext context) {
        return authors.existsByName(command.name())
            ? List.of(ValidationResult.error(
                "An author with that name is already registered.",
                List.of("name")))
            : List.of();
    }
}

@Configuration
public class RegisterAuthorValidation {
    @Bean
    public CommandValidator<RegisterAuthor> registerAuthorValidator(AuthorRepository authors) {
        return new BlockingCommandValidatorAdapter<>(new RegisterAuthorValidator(authors));
    }
}
```
