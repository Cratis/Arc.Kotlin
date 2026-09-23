```kotlin
@Bean
@Order(10)
public fun auditFilter(): CommandFilter = AuditCommandFilter()

@Bean
@Order(20)
public fun billingFilter(): CommandFilter = BillingCommandFilter()
```
