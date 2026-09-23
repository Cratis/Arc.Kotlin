```kotlin
data class ApplicationIdentity(val displayName: String)

@Bean
fun identityDetails(): IdentityDetailsProvider<ApplicationIdentity> =
    object : IdentityDetailsProvider<ApplicationIdentity> {
        override val detailsType = ApplicationIdentity::class.java

        override suspend fun provide(context: IdentityProviderContext): IdentityDetails<ApplicationIdentity> =
            IdentityDetails(
                isUserAuthorized = context.claims.any { it.type == "role" && it.value == "member" },
                details = ApplicationIdentity(context.name)
            )
    }
```
