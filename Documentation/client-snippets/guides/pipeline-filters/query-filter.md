```kotlin
public class BillingQueryFilter : QueryFilter {
    override suspend fun execute(context: QueryContext): QueryResult<*> {
        if (!context.queryName.value.startsWith(BILLING_PACKAGE)) {
            return QueryResult.success<Any>(context.correlationId)
        }
        if (context.principal.isInRole(BILLING_ROLE)) {
            return QueryResult.success<Any>(context.correlationId)
        }
        return QueryResult.unauthorized<Any>(context.correlationId)
    }

    private companion object {
        const val BILLING_PACKAGE = "com.example.features.billing"
        const val BILLING_ROLE = "billing"
    }
}
```
