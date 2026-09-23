```kotlin
data class Input(
    @IgnoreValidation val property: String?,
    @field:IgnoreValidation val field: String?,
    @get:IgnoreValidation val getter: String?,
    val sibling: String?
)
```
