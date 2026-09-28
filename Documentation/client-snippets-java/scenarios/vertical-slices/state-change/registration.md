```java
import io.cratis.arc.artifacts.Command;
import io.cratis.arc.artifacts.CommandKey;
import io.cratis.arc.authorization.AllowAnonymous;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandValidator;
import io.cratis.arc.java.BlockingCommandValidator;
import io.cratis.arc.java.BlockingCommandValidatorAdapter;
import io.cratis.arc.results.ValidationResult;
import io.cratis.chronicle.events.EventType;
import java.util.ArrayList;
import java.util.List;
import kotlin.Pair;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Authors/Registration/AuthorRegistered.java
/** Records an author's registration with their first and last names. */
@EventType
public record AuthorRegistered(AuthorName firstName, AuthorName lastName) { }

// Authors/Registration/RegisterAuthorValidator.java
public final class RegisterAuthorValidator implements BlockingCommandValidator<RegisterAuthor> {
    @Override
    public Class<RegisterAuthor> getCommandType() {
        return RegisterAuthor.class;
    }

    @Override
    public List<ValidationResult> validate(RegisterAuthor command, CommandContext context) {
        var results = new ArrayList<ValidationResult>();
        if (command.firstName().value().isBlank()) {
            results.add(ValidationResult.error("First name is required", List.of("firstName")));
        }
        if (command.lastName().value().isBlank()) {
            results.add(ValidationResult.error("Last name is required", List.of("lastName")));
        }
        return results;
    }
}

// Authors/Registration/RegistrationValidation.java
@Configuration
public class RegistrationValidation {
    @Bean
    public CommandValidator<RegisterAuthor> registerAuthorValidator() {
        return new BlockingCommandValidatorAdapter<>(new RegisterAuthorValidator());
    }
}

// Authors/Registration/RegisterAuthor.java
@Command
@AllowAnonymous
public record RegisterAuthor(@CommandKey AuthorId id, AuthorName firstName, AuthorName lastName) {
    public Pair<AuthorId, AuthorRegistered> handle() {
        return new Pair<>(id, new AuthorRegistered(firstName, lastName));
    }
}
```
