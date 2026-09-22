```java
@BeforeEach
void establish() {
    ChronicleCommandScenarios.givenChronicle(scenario)
        .events(
            accountId,
            new MoneyDeposited(new BigDecimal("100")),
            new MoneyDeposited(new BigDecimal("50")));
}
```
