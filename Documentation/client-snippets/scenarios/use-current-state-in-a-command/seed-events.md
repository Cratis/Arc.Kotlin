```kotlin
@BeforeEach
fun establish() {
    scenario.givenChronicle()
        .events(accountId, MoneyDeposited(BigDecimal("100")), MoneyDeposited(BigDecimal("50")))
}
```
