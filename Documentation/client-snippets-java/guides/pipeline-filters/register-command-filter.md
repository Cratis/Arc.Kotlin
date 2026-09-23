```java
@Bean
public CommandFilter billingCommandFilter() {
    return new BlockingCommandFilterAdapter(new BillingCommandFilter());
}
```
