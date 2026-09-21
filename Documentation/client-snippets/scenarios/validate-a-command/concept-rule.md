```kotlin
@Component
class AuthorNameValidator : ConceptValidator<AuthorName> {
    override val conceptType: Class<AuthorName> = AuthorName::class.java

    override fun validate(concept: AuthorName): List<ValidationResult> =
        if (concept.value().isBlank()) {
            listOf(ValidationResult.error("An author needs a name."))
        } else {
            emptyList()
        }
}
```
