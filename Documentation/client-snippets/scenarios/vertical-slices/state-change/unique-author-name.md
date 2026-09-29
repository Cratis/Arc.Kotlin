```kotlin
import io.cratis.chronicle.constraints.Constraint
import io.cratis.chronicle.constraints.IConstraint
import io.cratis.chronicle.constraints.IConstraintBuilder

// Authors/Registration/Registration.kt (continued)
@Constraint
class UniqueAuthorName : IConstraint {
    override fun define(builder: IConstraintBuilder) {
        builder.unique { unique ->
            unique
                .on(AuthorRegistered::class, AuthorRegistered::firstName, AuthorRegistered::lastName)
                .withMessage("An author with that name is already registered")
        }
    }
}
```
