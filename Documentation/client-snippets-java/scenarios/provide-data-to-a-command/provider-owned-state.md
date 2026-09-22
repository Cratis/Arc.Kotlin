```java
// An Optional parameter preserves owned read-model absence as Optional.empty().
public ShippingQuote provide(Optional<OrderView> order, ShippingRates rates) {
    return order
        .map(view -> rates.quote(view.destination(), view.totalWeight()))
        .orElse(ShippingQuote.NONE);
}
```
