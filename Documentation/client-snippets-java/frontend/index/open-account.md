```java
@Command
public record OpenAccount(AccountId id, AccountHolder owner) {
    public void handle(@FromServices AccountRepository accounts) {
        accounts.save(new Account(id, owner));
    }
}
```
