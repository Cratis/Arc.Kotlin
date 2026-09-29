```kotlin
import io.cratis.chronicle.constraints.Constraint
import io.cratis.chronicle.constraints.IConstraint
import io.cratis.chronicle.constraints.IConstraintBuilder

// Members/Registration/Registration.kt (continued)
@Constraint
class UniqueMemberName : IConstraint {
    override fun define(builder: IConstraintBuilder) {
        builder.unique { unique ->
            unique
                .on(MemberRegistered::class, MemberRegistered::firstName, MemberRegistered::lastName)
                .withMessage("A member with that name is already registered")
        }
    }
}
```
