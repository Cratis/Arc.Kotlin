```java
@Bean
@Order(10)
AsyncAuthenticationHandler bearerAuthentication() {
    return context -> {
        String value = context.header("Authorization");
        AuthenticationResult result;
        if (value == null) {
            result = AuthenticationResult.ANONYMOUS;
        } else if (value.equals("Bearer valid-token")) {
            result = AuthenticationResult.succeeded(
                new ArcPrincipal("Ada", true, Set.of("admin"), "user-42", List.of(), "Bearer"));
        } else {
            result = AuthenticationResult.failed(AuthenticationFailureReason.of("invalid-token"));
        }
        return CompletableFuture.completedFuture(result);
    };
}
```
