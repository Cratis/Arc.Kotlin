```kotlin
@Command
data class OpenDebitAccount(val accountId: AccountId, val name: AccountName, val owner: CustomerId) {
    suspend fun handle(@FromServices accounts: AccountService) {
        accounts.open(accountId, name, owner)
    }
}
```
