```java
@Command
@AllowCallerValidationSeverity(maximum = ValidationResultSeverity.Error)
public record PreviewImport(String source) {
    public void handle() {
        // Error-level validation on this preview command is advisory.
    }
}
```
