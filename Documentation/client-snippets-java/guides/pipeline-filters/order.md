```java
@Bean
@Order(10)
public CommandFilter auditFilter() {
    return new BlockingCommandFilterAdapter(new AuditCommandFilter());
}

@Bean
@Order(20)
public CommandFilter billingFilter() {
    return new BlockingCommandFilterAdapter(new BillingCommandFilter());
}
```
