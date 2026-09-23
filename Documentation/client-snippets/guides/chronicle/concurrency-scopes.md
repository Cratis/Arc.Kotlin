```kotlin
fun handle(): EventsWithConcurrencyScopes = eventsWithConcurrencyScopes {
    event("account-42", FundsWithdrawn(100))
    event("ledger-2025", LedgerEntryAdded("account-42", 100))
    concurrencyScope("account-42") {
        withEventSourceId()
    }
}
```
