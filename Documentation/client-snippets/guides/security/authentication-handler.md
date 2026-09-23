```kotlin
@Bean
@Order(10)
fun bearerAuthentication(): AuthenticationHandler = AuthenticationHandler { context ->
    when (context.header("Authorization")) {
        null -> AuthenticationResult.ANONYMOUS
        "Bearer valid-token" -> AuthenticationResult.succeeded(
            ArcPrincipal(
                name = "Ada",
                isAuthenticated = true,
                roles = setOf("admin"),
                id = "user-42",
                authenticationScheme = "Bearer"
            )
        )
        else -> AuthenticationResult.failed(AuthenticationFailureReason.of("invalid-token"))
    }
}
```
