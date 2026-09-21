```kotlin
@ReadModel
data class DebitAccount(val id: AccountId, val name: AccountName) {
    companion object {
        @JvmStatic
        fun startingWith(
            filter: String?,
            @FromServices accounts: DebitAccountRepository
        ): List<DebitAccount> = accounts.findByNameStartingWith(filter ?: "")
    }
}
```
