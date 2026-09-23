```java
public final class BillingCommandFilter implements BlockingCommandFilter {
    private static final String BILLING_PACKAGE = "com.example.features.billing";
    private static final String BILLING_ROLE = "billing";

    @Override
    public CommandResult<?> execute(CommandContext context) {
        if (!context.getCommandType().getName().startsWith(BILLING_PACKAGE)
            || context.getPrincipal().isInRole(BILLING_ROLE)) {
            return CommandResult.success(context.getCorrelationId());
        }
        return CommandResult.unauthorized(
            context.getCorrelationId(),
            "Role '" + BILLING_ROLE + "' is required for commands in '" + BILLING_PACKAGE + "'."
        );
    }
}
```
