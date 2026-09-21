```kotlin
@BeforeEach
fun establish() {
    scenario.withReadModelForKey(
        AccountBalance::class.java,
        accountId,
        AccountBalance(BigDecimal("150"))
    )
}
```
