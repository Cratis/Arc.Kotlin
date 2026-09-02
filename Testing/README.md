# Arc Testing

`io.cratis:arc-testing` runs generated or manual Arc command handlers and query performers in-process through the real `DefaultCommandPipeline`, `DefaultQueryPipeline`, and `DefaultObservableQueryPipeline`. It does not start Spring Boot and it does not substitute fake handlers or performers. The optional Chronicle integration contributes a `ServiceLoader` scenario extender with an in-memory event log, ordered given history, append assertions, and deterministic constraint/concurrency failures; it does not start a Chronicle kernel.

The public assertions throw `AssertionError` and do not depend on JUnit or Kotest. Arc JSON round trips are enabled by default: commands cross the same serializer boundary before execution, and query arguments and returned data are round-tripped when their runtime shape can be preserved.

## Kotlin

Select an exact command or query from the generated module. Missing and duplicate artifacts fail during scenario setup.

```kotlin
import io.cratis.arc.generated.ApplicationArcArtifactModule
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.testing.CommandScenario
import io.cratis.arc.testing.QueryScenario

val module = ApplicationArcArtifactModule()

val commandResult = CommandScenario(module, RegisterCustomer::class.java)
    .addService(CustomerRepository::class.java, repository)
    .withPrincipal(testPrincipal)
    .withTenant("tenant-one")
    .addValidator(registerCustomerValidator)
    .execute(RegisterCustomer("Ada"))

commandResult
    .shouldSucceed()
    .shouldHaveResponse(CustomerId::class.java)

val queryResult = QueryScenario<List<Customer>>(
    module,
    FullyQualifiedQueryName("com.example.customers.Customers.all")
)
    .addService(CustomerRepository::class.java, repository)
    .perform(
        arguments = mapOf("name" to "Ada"),
        paging = QueryPaging(0, 25),
        sorting = QuerySorting("name", QuerySortDirection.ASCENDING)
    )

queryResult.shouldBeReady().shouldSucceed()
queryResult.shouldHavePaging(0, 25, 1)
```

Manual `CommandHandler` and `QueryPerformer` overloads are available for framework and extension tests. They still use the real registries and pipelines.

`ScenarioServiceResolver` is immutable. Its builder rejects duplicate exact `Class` keys:

```kotlin
val services = ScenarioServiceResolver.builder()
    .put(CustomerRepository::class.java, repository)
    .put(clock)
    .build()
```

Pipeline customization methods return the scenario for Kotlin DSL-style chaining. Commands support filters, scopes, response handlers, policies, validators, principal, tenant, correlation ID, services, and allowed validation severity. Queries support filters, policies, validators, the same execution context, paging, and sorting. Use `withSerializationRoundTrip(false)` only when the test intentionally must bypass the wire boundary.

`ObservableQueryScenario` collects an explicitly bounded emission count with a timeout and supports renderers, read-model interceptors, and per-emission guards in addition to the shared query configuration.

Command, query, and observable-query scenarios can resolve tenancy from explicit host-neutral context:

```kotlin
scenario.withTenantResolution(
    HeaderTenantIdResolver(),
    TenantResolutionContext(headers = mapOf("X-Cratis-Tenant-Id" to "tenant-one"))
)
```

The resolved ID becomes both tenant ID and namespace by default; pass the optional namespace argument to override it. No scenario or resolver uses thread-local request state.

## Java

Java can use closeable blocking bridges with an owned bounded scope:

```java
ArcArtifactModule module = new ApplicationArcArtifactModule();

try (BlockingCommandScenario<RegisterCustomer> scenario =
         new BlockingCommandScenario<>(module, RegisterCustomer.class)) {
    CustomerId id = scenario.execute(new RegisterCustomer("Ada"))
        .shouldSucceed()
        .shouldHaveResponse(CustomerId.class);
}

try (BlockingQueryScenario<List<Customer>> scenario =
         new BlockingQueryScenario<>(
             module,
             new FullyQualifiedQueryName("com.example.customers.Customers.all"))) {
    scenario.perform(Map.of("name", "Ada")).shouldSucceed();
}
```

For non-blocking Java tests, pass a caller-owned structured or bounded `CoroutineScope` and use the `CompletionStage` bridges:

```java
AsyncCommandScenario<RegisterCustomer> scenario =
    new AsyncCommandScenario<>(module, RegisterCustomer.class, callerOwnedScope);

CompletionStage<CommandScenarioResult<Object>> result =
    scenario.execute(new RegisterCustomer("Ada"));
```

Canceling a returned future cancels its child coroutine. The asynchronous bridges never use `GlobalScope` or `ThreadLocal` state.

See the [testing guide](../Documentation/guides/testing.md) for Chronicle Kotlin extensions and their `ChronicleCommandScenarios` Java static bridges.
