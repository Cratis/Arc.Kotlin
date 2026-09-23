```java
@Bean
@Order(20)
AsyncAuthenticationHandler apiKeyAuthentication() {
    return context -> {
        String value = context.header("X-Api-Key");
        AuthenticationResult result;
        if (value == null) {
            result = AuthenticationResult.ANONYMOUS;
        } else if (value.equals("valid-key")) {
            result = AuthenticationResult.succeeded(
                new ArcPrincipal("Ada", true, Set.of("operator"), "user-42", List.of(), "ApiKey"));
        } else {
            result = AuthenticationResult.failed(AuthenticationFailureReason.of("invalid-api-key"));
        }
        return CompletableFuture.completedFuture(result);
    };
}
```
