```java
@ReadModel
public record DebitAccount(AccountId id, AccountName name) {
    public static List<DebitAccount> startingWith(
        String filter,
        @FromServices DebitAccountRepository accounts
    ) {
        return accounts.findByNameStartingWith(filter == null ? "" : filter);
    }
}
```
