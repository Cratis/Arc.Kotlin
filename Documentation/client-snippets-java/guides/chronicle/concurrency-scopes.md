```java
public EventsWithConcurrencyScopes handle() {
    return EventsWithConcurrencyScopes.builder()
        .event("account-42", new FundsWithdrawn(100))
        .event("ledger-2025", new LedgerEntryAdded("account-42", 100))
        .concurrencyScope("account-42", ConcurrencyScopeBuilder::withEventSourceId)
        .build();
}
```
