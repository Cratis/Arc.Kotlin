```kotlin
@Command
data class SubmitOrder(@CommandKey val id: UUID) {
    suspend fun handle(order: OrderView, @FromServices orders: OrderSubmissions) {
        orders.submit(order.id)
    }
}
```
