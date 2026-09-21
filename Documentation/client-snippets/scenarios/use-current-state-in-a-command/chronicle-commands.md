```kotlin
@Command
data class SettleLedger(@CommandKey val ledgerId: LedgerId) {
    fun handle(balance: LedgerBalance): LedgerSettled = LedgerSettled(balance.balance)
}

@Command
data class WithdrawFunds(@CommandKey val accountId: AccountId, val amount: BigDecimal) {
    fun handle(balance: AccountBalance): FundsWithdrawn =
        FundsWithdrawn(amount, balance.balance - amount)
}
```
