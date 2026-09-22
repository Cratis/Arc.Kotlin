```java
public interface ProfileDirectory {
    CompletionStage<Boolean> isEmailAllowed(String email);
}

public final class UpdateProfileDirectoryRules implements AsyncCommandValidator<UpdateProfile> {
    private final ProfileDirectory profiles;

    public UpdateProfileDirectoryRules(ProfileDirectory profiles) {
        this.profiles = profiles;
    }

    @Override
    public Class<UpdateProfile> getCommandType() {
        return UpdateProfile.class;
    }

    @Override
    public CompletionStage<List<ValidationResult>> validate(
        UpdateProfile command,
        CommandContext context
    ) {
        return profiles.isEmailAllowed(command.email()).thenApply(allowed -> allowed
            ? List.of()
            : List.of(ValidationResult.error(
                "This email cannot be used for this profile.",
                List.of("email"))));
    }
}

@Configuration
public class UpdateProfileValidation {
    @Bean
    public CommandValidator<UpdateProfile> updateProfileDirectoryRules(ProfileDirectory profiles) {
        return new AsyncCommandValidatorAdapter<>(new UpdateProfileDirectoryRules(profiles));
    }
}
```
