```java
@BeforeEach
void establish() {
    scenario.withReadModelForKey(
        AccountBalance.class,
        accountId,
        new AccountBalance(new BigDecimal("150")));
}
```
