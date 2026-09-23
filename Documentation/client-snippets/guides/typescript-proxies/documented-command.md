```kotlin
/**
 * Creates an order for a customer.
 *
 * @property customerId Customer placing the order.
 */
@Command
public data class CreateOrder(
    /** Stable order identifier. */
    @CommandKey public val orderId: String,
    public val customerId: String
)
```
