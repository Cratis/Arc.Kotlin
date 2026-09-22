```java
@Command
public record SettleLedger(@CommandKey LedgerId ledgerId) {
    public LedgerSettled handle(LedgerBalance balance) {
        return new LedgerSettled(balance.balance());
    }
}

@Command
public record WithdrawFunds(@CommandKey AccountId accountId, BigDecimal amount) {
    public FundsWithdrawn handle(AccountBalance balance) {
        return new FundsWithdrawn(amount, balance.balance().subtract(amount));
    }
}
```
