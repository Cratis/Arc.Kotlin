```kotlin
@Bean("activeSubscription")
fun activeSubscription(): AuthorizationPolicy = AuthorizationPolicy { principal ->
    if (principal.claims.any { it.type == "subscription" && it.value == "active" }) {
        AuthorizationResult.success()
    } else {
        AuthorizationResult.failure("An active subscription is required.")
    }
}
```
