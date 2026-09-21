```kotlin
data class LibraryIdentity(val memberId: String, val role: String, val displayName: String)

@Bean
fun identityDetails(members: MemberRepository): IdentityDetailsProvider<LibraryIdentity> =
    object : IdentityDetailsProvider<LibraryIdentity> {
        override val detailsType = LibraryIdentity::class.java

        // Look the user up in your own data, keyed by the provider's id.
        override suspend fun provide(context: IdentityProviderContext): IdentityDetails<LibraryIdentity> {
            val member = members.bySubject(context.id)
                ?: return IdentityDetails(isUserAuthorized = false, details = LibraryIdentity("", "", ""))

            return IdentityDetails(
                isUserAuthorized = true,
                details = LibraryIdentity(member.id, member.role, member.name)
            )
        }
    }
```
