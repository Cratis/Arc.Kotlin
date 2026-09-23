```java
public final class ApplicationFailure extends RuntimeException implements ValidationFailure {
    private final List<ValidationResult> validationResults;

    public ApplicationFailure(List<ValidationResult> validationResults) {
        super("Internal diagnostic");
        this.validationResults = validationResults;
    }

    @Override
    public List<ValidationResult> getValidationResults() {
        return validationResults;
    }
}
```
