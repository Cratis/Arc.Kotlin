```java
import io.cratis.arc.artifacts.Command;
import io.cratis.arc.validation.FluentModelValidator;

public record ProfileDetails(String name, String email) {
}

public final class UpdateProfileRules extends FluentModelValidator<UpdateProfile> {
    public UpdateProfileRules() {
        super(UpdateProfile.class);
        ruleFor("name").notEmpty().length(3, 100);
        ruleFor("email").notEmpty().emailAddress();
    }
}

@Command
public record UpdateProfile(String name, String email) {
    public ProfileDetails handle() {
        return new ProfileDetails(name, email);
    }
}
```
