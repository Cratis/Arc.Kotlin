```java
public final class BillingQueryFilter implements BlockingQueryFilter {
    private static final String BILLING_PACKAGE = "com.example.features.billing";
    private static final String BILLING_ROLE = "billing";

    @Override
    public QueryResult<?> execute(QueryContext context) {
        if (!context.getQueryName().value().startsWith(BILLING_PACKAGE)
            || context.getPrincipal().isInRole(BILLING_ROLE)) {
            return QueryResult.success(context.getCorrelationId());
        }
        return QueryResult.unauthorized(context.getCorrelationId());
    }
}
```
