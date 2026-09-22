```kotlin
@Command
data class ChangeShippingAddress(
    @CommandKey val orderId: OrderId,
    val address: Address
) {
    suspend fun handle(@FromServices orders: OrderRepository) {
        orders.updateAddress(orderId, address)
    }
}
```
