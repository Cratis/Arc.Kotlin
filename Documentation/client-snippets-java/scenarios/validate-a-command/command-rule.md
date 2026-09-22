```java
public final class RegisterAuthorValidator implements BlockingCommandValidator<RegisterAuthor> {
    @Override
    public Class<RegisterAuthor> getCommandType() {
        return RegisterAuthor.class;
    }

    @Override
    public List<ValidationResult> validate(RegisterAuthor command, CommandContext context) {
        var name = command.name().value();
        if (name.isBlank()) {
            return List.of(ValidationResult.error("An author needs a name.", List.of("name")));
        }
        if (name.length() > 200) {
            return List.of(ValidationResult.error(
                "An author name is at most 200 characters.",
                List.of("name")));
        }

        return List.of();
    }
}

@Configuration
public class RegisterAuthorValidation {
    @Bean
    public CommandValidator<RegisterAuthor> registerAuthorValidator() {
        return new BlockingCommandValidatorAdapter<>(new RegisterAuthorValidator());
    }
}
```
