---
title: Apply cross-cutting rules with pipeline filters
description: Write command and query filters that decide for a whole feature, in Kotlin or ordinary Java, and order them deliberately.
---

Some rules belong to an artifact. "This command needs a title" is about that command, and it lives
on that command.

Other rules belong to a whole area. "Everything in the billing feature needs the billing role" is
not really about any one command — it is about the feature — and writing it on each command in turn
means the twenty-first command is the one somebody forgets. A filter is where that kind of rule
goes: one declaration that every command or query in the pipeline passes through.

## Write a command filter

A filter sees the resolved [`CommandContext`](commands.md) — the command instance, its type, the
principal authentication already produced, the correlation identifier, the tenant — and returns a
result that is merged into the outcome. Returning anything unsuccessful stops the command before its
handler is called:

```kotlin
public class BillingCommandFilter : CommandFilter {
    override suspend fun execute(context: CommandContext): CommandResult<*> {
        if (!context.commandType.name.startsWith(BILLING_PACKAGE)) {
            return CommandResult.success(context.correlationId)
        }
        if (context.principal.isInRole(BILLING_ROLE)) {
            return CommandResult.success(context.correlationId)
        }
        return CommandResult.unauthorized(
            context.correlationId,
            "Role '$BILLING_ROLE' is required for commands in '$BILLING_PACKAGE'."
        )
    }

    private companion object {
        const val BILLING_PACKAGE = "com.example.features.billing"
        const val BILLING_ROLE = "billing"
    }
}
```

Register it as a bean and it applies to every command:

```kotlin
@Bean
public fun billingCommandFilter(): CommandFilter = BillingCommandFilter()
```

A filter that does not apply to the command in front of it returns success and gets out of the way.
That first check is what keeps a feature's rule scoped to the feature.

## Write a query filter

The same shape, against `QueryContext`. The query's fully qualified name is what you match on,
because a query is a method rather than a type:

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
}
```

A query filter runs for observable queries too, at subscription time — so a rule written once covers
both the one-shot `GET` and the live subscription.

## In ordinary Java

Java implementations never implement a suspending method. Pick the adapter that matches how your
code is already written:

| Your code | Implement | Register with |
| --- | --- | --- |
| Synchronous | `BlockingCommandFilter` / `BlockingQueryFilter` | `BlockingCommandFilterAdapter` / `BlockingQueryFilterAdapter` |
| `CompletionStage` | `AsyncCommandFilter` / `AsyncQueryFilter` | `AsyncCommandFilterAdapter` / `AsyncQueryFilterAdapter` |

```java
public final class BillingCommandFilter implements BlockingCommandFilter {
    @Override
    public CommandResult<?> execute(CommandContext context) {
        if (!context.getCommandType().getName().startsWith(BILLING_PACKAGE)
            || context.getPrincipal().isInRole(BILLING_ROLE)) {
            return CommandResult.success(context.getCorrelationId());
        }
        return CommandResult.unauthorized(context.getCorrelationId(), "Billing role required.");
    }
}

@Bean
public CommandFilter billingCommandFilter() {
    return new BlockingCommandFilterAdapter(new BillingCommandFilter());
}
```

A blocking filter occupies a request thread for as long as it runs. Keep it to decisions it can make
from the context it already has; if it needs to call something remote, use the `CompletionStage`
form instead.

## Order them deliberately

Filters are collected in Spring `@Order` sequence, and that order is the complete rule — there is no
hidden precedence:

```kotlin
@Bean
@Order(10)
public fun auditFilter(): CommandFilter = AuditCommandFilter()

@Bean
@Order(20)
public fun billingFilter(): CommandFilter = BillingCommandFilter()
```

Authorization filters are the one exception: a filter implementing `AuthorizationCommandFilter` or
`AuthorizationQueryFilter` runs before the rest regardless of its order, so a rule that denies access
is never reached after a filter that has already caused an effect. Implement that interface when your
filter's job is to authorize, and the ordinary one when it is not.

## When not to reach for a filter

A filter is the wrong tool when the rule is about one artifact. `@Authorize` and `@Roles` on the
command or the query say the same thing closer to the code they govern, are visible in the generated
client and the OpenAPI document, and do not require a reader to go looking for a bean that might
apply. See [authentication and authorization](security.md).

Use a filter when the rule genuinely spans artifacts, and prefer matching on something structural —
a package prefix — over a list of names somebody has to maintain.

## See it running

The [samples](../../Samples/README.md) include a Cross-Cutting Authorization page backed by exactly
this pattern, in
[Kotlin](../../Samples/Kotlin/SpringBoot/src/main/kotlin/io/cratis/arc/samples/kotlin/springboot/features/crosscuttingauthorization/CrossCuttingAuthorizationFilters.kt)
and in
[Java](../../Samples/Java/SpringBoot/src/main/java/io/cratis/arc/samples/javaspringboot/features/crosscuttingauthorization/CrossCuttingAuthorizationFilters.java).
Run `./Samples/run.sh`, open the page, and add or remove the role in the toolbar to watch both the
command and the query change their answer.
