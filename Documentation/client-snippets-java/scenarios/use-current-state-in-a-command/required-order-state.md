```java
@Command
public record SubmitOrder(@CommandKey UUID id) {
    public void handle(OrderView order, @FromServices OrderSubmissions orders) {
        orders.submit(order.id());
    }
}
```
