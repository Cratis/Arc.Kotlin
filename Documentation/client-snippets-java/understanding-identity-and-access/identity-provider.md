```java
public record LibraryIdentity(String memberId, String role, String displayName) {
}

@Bean
public IdentityDetailsProvider<LibraryIdentity> identityDetails(MemberRepository members) {
    return new AsyncIdentityDetailsProviderAdapter<>(new AsyncIdentityDetailsProvider<LibraryIdentity>() {
        @Override
        public Class<LibraryIdentity> getDetailsType() {
            return LibraryIdentity.class;
        }

        // Look the user up in your own data, keyed by the provider's id.
        @Override
        public CompletionStage<IdentityDetails<LibraryIdentity>> provide(IdentityProviderContext context) {
            var member = members.bySubject(context.getId());
            if (member == null) {
                return CompletableFuture.completedFuture(
                    new IdentityDetails<>(false, new LibraryIdentity("", "", "")));
            }

            return CompletableFuture.completedFuture(new IdentityDetails<>(
                true,
                new LibraryIdentity(member.id(), member.role(), member.name())));
        }
    });
}
```
