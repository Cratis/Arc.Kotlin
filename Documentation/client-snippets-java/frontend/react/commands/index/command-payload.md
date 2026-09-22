```java
@Command
public record OpenDebitAccount(AccountId accountId, AccountName name, CustomerId owner) {
    public void handle(@FromServices AccountService accounts) {
        accounts.open(accountId, name, owner);
    }
}
```
