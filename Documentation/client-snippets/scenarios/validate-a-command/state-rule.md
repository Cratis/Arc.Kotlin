```kotlin
@Component
class SubmitOrderValidator(
    private val orders: OrderViewRepository
) : CommandValidator<SubmitOrder> {
    override val commandType: Class<SubmitOrder> = SubmitOrder::class.java

    override suspend fun validate(
        command: SubmitOrder,
        context: CommandContext
    ): List<ValidationResult> {
        val order = orders.findById(command.id).orElse(null)
            ?: return listOf(ValidationResult.error("Order does not exist.", listOf("id")))

        return if (order.status == OrderStatus.READY_FOR_SUBMISSION) {
            emptyList()
        } else {
            listOf(
                ValidationResult.error(
                    "Only orders that are ready for submission can be submitted.",
                    listOf("id")
                )
            )
        }
    }
}
```
