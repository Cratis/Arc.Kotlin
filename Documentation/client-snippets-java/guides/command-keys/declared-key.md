```java
@Command
public record ChangeShippingAddress(@CommandKey OrderId orderId, Address address) {
    public void handle(@FromServices OrderRepository orders) {
        orders.updateAddress(orderId, address);
    }
}
```
