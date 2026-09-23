```kotlin
@Command
data class Withdraw(
    @CommandKey val accountId: String,
    val amount: Int
) {
    fun handle(balance: AccountBalance): FundsWithdrawn {
        require(balance.available >= amount) { "Insufficient funds." }
        return FundsWithdrawn(amount)
    }
}
```
