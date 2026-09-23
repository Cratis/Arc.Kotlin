```java
/**
 * Creates an order for a customer.
 *
 * @param customerId Customer placing the order.
 */
@Command
public record CreateOrder(
    /** Stable order identifier. */
    @CommandKey String orderId,
    String customerId
) { }
```
