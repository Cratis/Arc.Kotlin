```kotlin
suspend fun provide(order: OrderView?, rates: ShippingRates): ShippingQuote =
    if (order == null) {
        ShippingQuote.NONE
    } else {
        rates.quote(order.destination, order.totalWeight)
    }
```
