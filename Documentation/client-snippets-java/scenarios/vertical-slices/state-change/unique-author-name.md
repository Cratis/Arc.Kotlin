```java
import io.cratis.chronicle.constraints.Constraint;
import io.cratis.chronicle.constraints.IConstraint;
import io.cratis.chronicle.constraints.IConstraintBuilder;
import io.cratis.chronicle.java.UniqueConstraintBuilderJavaBridge;

// Authors/Registration/UniqueAuthorName.java
@Constraint
public class UniqueAuthorName implements IConstraint {
    @Override
    public void define(IConstraintBuilder builder) {
        builder.unique(unique -> {
            UniqueConstraintBuilderJavaBridge
                .on(unique, AuthorRegistered.class, "firstName", "lastName")
                .withMessage("An author with that name is already registered");
        });
    }
}
```
