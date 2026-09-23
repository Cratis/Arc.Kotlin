```kotlin
@ReadModel
@Authorize
public data class AuthenticationQueryItem(public val message: String) {
    public companion object {
        // Overrides the class: anyone may subscribe to this one.
        @JvmStatic
        @AllowAnonymous
        public fun anonymous(@FromServices source: AuthenticationQuerySource): Flow<AuthenticationQueryItem> =
            source.observeAnonymous()

        // Declares nothing, so the class-level @Authorize applies.
        @JvmStatic
        public fun authenticated(@FromServices source: AuthenticationQuerySource): Flow<AuthenticationQueryItem> =
            source.observeAuthenticated()
    }
}
```
