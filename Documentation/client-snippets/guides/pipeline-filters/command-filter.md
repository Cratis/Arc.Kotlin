```kotlin
public class BillingCommandFilter : CommandFilter {
    override suspend fun execute(context: CommandContext): CommandResult<*> {
        if (!context.commandType.name.startsWith(BILLING_PACKAGE)) {
            return CommandResult.success(context.correlationId)
        }
        if (context.principal.isInRole(BILLING_ROLE)) {
            return CommandResult.success(context.correlationId)
        }
        return CommandResult.unauthorized(
            context.correlationId,
            "Role '$BILLING_ROLE' is required for commands in '$BILLING_PACKAGE'."
        )
    }

    private companion object {
        const val BILLING_PACKAGE = "com.example.features.billing"
        const val BILLING_ROLE = "billing"
    }
}
```
