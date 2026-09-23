```kotlin
data class AuditEntry(val orderId: String)
data class OrderReceipt(val orderId: String)

@Command
data class CreateOrder(val orderId: String) {
    fun handle(): Pair<AuditEntry, OrderReceipt> =
        AuditEntry(orderId) to OrderReceipt(orderId)
}

@Component
@HandlesCommandResponseValues(AuditEntry::class)
class AuditEntryResponseHandler : CommandResponseValueHandler {
    override fun canHandle(context: CommandContext, value: Any): Boolean = value is AuditEntry

    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
        if (value !is AuditEntry) {
            return CommandResult.error(context.correlationId, "Unsupported response value.")
        }
        // Persist value with an injected service in a real handler.
        return CommandResult.success(context.correlationId)
    }
}
```

