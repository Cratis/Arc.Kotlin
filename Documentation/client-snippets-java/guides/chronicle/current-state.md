```java
@Command
public record Withdraw(@CommandKey String accountId, int amount) {
    public FundsWithdrawn handle(AccountBalance balance) {
        if (balance.available() < amount) {
            throw new IllegalArgumentException("Insufficient funds.");
        }
        return new FundsWithdrawn(amount);
    }
}
```
