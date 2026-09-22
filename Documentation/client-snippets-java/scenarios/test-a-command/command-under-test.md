```java
public interface AuthorRegistration {
    void register(AuthorId id, AuthorName name);
}

@Command
public record RecordAuthor(AuthorId id, AuthorName name) {
    public void handle(AuthorRegistration registration) {
        registration.register(id, name);
    }
}

public final class RecordAuthorValidator implements BlockingCommandValidator<RecordAuthor> {
    @Override
    public Class<RecordAuthor> getCommandType() {
        return RecordAuthor.class;
    }

    @Override
    public List<ValidationResult> validate(RecordAuthor command, CommandContext context) {
        return command.name().value().isBlank()
            ? List.of(ValidationResult.error("An author needs a name.", List.of("name")))
            : List.of();
    }
}

// A Java validator is an ordinary class; the adapter is what Arc discovers as a bean.
@Configuration
public class RecordAuthorValidation {
    @Bean
    public CommandValidator<RecordAuthor> recordAuthorValidator() {
        return new BlockingCommandValidatorAdapter<>(new RecordAuthorValidator());
    }
}
```
