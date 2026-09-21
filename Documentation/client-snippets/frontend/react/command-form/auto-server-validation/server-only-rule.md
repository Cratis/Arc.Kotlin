```kotlin
interface ProfileDirectory {
    suspend fun isEmailAllowed(email: String): Boolean
}

@Component
class UpdateProfileDirectoryRules(
    private val profiles: ProfileDirectory
) : CommandValidator<UpdateProfile> {
    override val commandType: Class<UpdateProfile> = UpdateProfile::class.java

    override suspend fun validate(
        command: UpdateProfile,
        context: CommandContext
    ): List<ValidationResult> =
        if (profiles.isEmailAllowed(command.email)) {
            emptyList()
        } else {
            listOf(
                ValidationResult.error("This email cannot be used for this profile.", listOf("email"))
            )
        }
}
```
