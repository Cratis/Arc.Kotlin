```java
record ApplicationIdentity(String displayName) { }

@Bean
AsyncIdentityDetailsProvider<ApplicationIdentity> identityDetails() {
    return new AsyncIdentityDetailsProvider<>() {
        @Override
        public Class<ApplicationIdentity> getDetailsType() {
            return ApplicationIdentity.class;
        }

        @Override
        public CompletionStage<IdentityDetails<ApplicationIdentity>> provide(IdentityProviderContext context) {
            boolean authorized = context.getClaims().stream()
                .anyMatch(claim -> claim.getType().equals("role") && claim.getValue().equals("member"));
            return CompletableFuture.completedFuture(
                new IdentityDetails<>(authorized, new ApplicationIdentity(context.getName())));
        }
    };
}
```
