```kotlin
@Command
data class OpenAccount(val id: AccountId, val owner: AccountHolder) {
    suspend fun handle(@FromServices accounts: AccountRepository) {
        accounts.save(Account(id, owner))
    }
}
```
