```kotlin
@Authorize(policy = "activeSubscription")
@Roles("member")
@Command
class UpdateProfile {
    fun handle(): Unit = Unit
}
```
