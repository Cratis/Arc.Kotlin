```kotlin
@Command
@AllowCallerValidationSeverity(maximum = ValidationResultSeverity.Error)
data class PreviewImport(val source: String) {
    fun handle() {
        // Error-level validation on this preview command is advisory.
    }
}
```
