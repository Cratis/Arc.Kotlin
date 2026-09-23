```java
@Bean("activeSubscription")
AuthorizationPolicy activeSubscription() {
    return new BlockingAuthorizationPolicyAdapter(principal -> principal.getClaims().stream()
        .anyMatch(claim -> claim.getType().equals("subscription") && claim.getValue().equals("active"))
            ? AuthorizationResult.success()
            : AuthorizationResult.failure("An active subscription is required."));
}
```
