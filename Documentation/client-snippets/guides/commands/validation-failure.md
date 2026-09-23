```kotlin
class ApplicationFailure(
    override val validationResults: List<ValidationResult>
) : RuntimeException("Internal diagnostic"), ValidationFailure
```
