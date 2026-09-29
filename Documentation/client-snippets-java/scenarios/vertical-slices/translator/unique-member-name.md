```java
import io.cratis.chronicle.constraints.Constraint;
import io.cratis.chronicle.constraints.IConstraint;
import io.cratis.chronicle.constraints.IConstraintBuilder;
import io.cratis.chronicle.java.UniqueConstraintBuilderJavaBridge;

// Members/Registration/UniqueMemberName.java
@Constraint
public class UniqueMemberName implements IConstraint {
    @Override
    public void define(IConstraintBuilder builder) {
        builder.unique(unique -> {
            UniqueConstraintBuilderJavaBridge
                .on(unique, MemberRegistered.class, "firstName", "lastName")
                .withMessage("A member with that name is already registered");
        });
    }
}
```
