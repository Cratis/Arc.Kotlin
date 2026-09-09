---
title: Test commands and queries in process
description: Exercise generated Arc artifacts through real pipelines without starting Spring Boot.
---

## Add the testing module

```kotlin
dependencies {
    testImplementation("io.cratis:arc-testing:<version>")
}
```

`arc-testing` uses the real `DefaultCommandPipeline` and `DefaultQueryPipeline`. It does not start Spring Boot or replace generated behavior with fake handlers. JSON round trips are enabled by default.

## Test with Kotlin

```kotlin
val module = TaskApplicationArcArtifactModule()
val result = CommandScenario(module, CreateTask::class.java)
    .addService(TaskRepository::class.java, repository)
    .execute(CreateTask("Try Arc"))

result.shouldSucceed().shouldHaveResponse(TaskCreated::class.java)
```

Select queries with `FullyQualifiedQueryName` and use `QueryScenario<T>`. Add services, validators, policies, filters, renderers, read-model interceptors, a principal, tenant, correlation ID, paging, or sorting through scenario methods. Use `ObservableQueryScenario<T>` to collect an explicitly bounded emission count with a timeout and to add per-emission guards. Disable serialization round trips only when the test intentionally bypasses the wire boundary.

Command and query results carry matching positive and negative assertions, so a test can pin which stage rejected an operation instead of only that it failed. `shouldBeAuthorized` and `shouldBeUnauthorized` cover authorization, `shouldBeValid` and `shouldBeInvalid` cover validation feedback, and `shouldHaveErrors` and `shouldHaveNoErrors` cover retained exception messages. Chaining `shouldBeAuthorized().shouldBeInvalid()` states that authorization passed and validation rejected the command, which `shouldFail` alone does not.

To exercise the same host-neutral tenancy contract an integration uses, provide both the resolver and explicit request context. The resolved tenant ID is also used as the namespace unless a namespace is supplied:

```kotlin
val result = CommandScenario(module, CreateTask::class.java)
    .withTenantResolution(
        HeaderTenantIdResolver(),
        TenantResolutionContext(headers = mapOf("X-Cratis-Tenant-Id" to "tenant-one"))
    )
    .execute(CreateTask("Try Arc"))
```

`QueryScenario` and `ObservableQueryScenario` expose the same `withTenantResolution` method.

## Pin a command-side read model

A command that takes a read-model parameter normally has it resolved by whichever store owns the type. In a test, pin the read model to a known state instead. No store, query pipeline, or Chronicle kernel is involved, and the pin flows through the same ownership registry a store would use:

```kotlin
val result = CommandScenario(module, RenameTask::class.java)
    .withReadModelForKey(TaskView::class.java, "task-1", TaskView("task-1", "Current title", 7))
    .execute(RenameTask("task-1", "Renamed title", 7))
```

`withReadModelForKey` restricts the pin to one command key, so the test also proves the command carries the key it should; any other key resolves to absence. Keys are compared after unwrapping `ConceptAs` wrappers, so a scalar pin matches a concept-typed command key. Use `withReadModel(type, readModel)` when the key is not the point of the test.

Pinning `null` pins deliberate absence, which is how the missing-model path is exercised — a required parameter fails with `dependencyUnavailable`, while a Kotlin nullable parameter observes `null` and a Java `Optional` parameter observes `Optional.empty()`:

```kotlin
val result = CommandScenario(module, RenameTask::class.java)
    .withReadModel(TaskView::class.java, null)
    .execute(RenameTask("missing", "Renamed title", 0))
```

Pins claim declared ownership, so they win over a fallback resolver. A read-model type can be pinned once without a key or once per key, not both, and `addReadModelResolver` remains available for a resolver that has to compute something.

## Test Chronicle commands without a kernel

Add both test support and the Chronicle starter. The starter registers its `CommandScenarioExtender` through `ServiceLoader`, so a `CommandScenario` automatically gets an in-memory Chronicle event log without starting Spring Boot or a Chronicle kernel.

```kotlin
val scenario = CommandScenario(module, RegisterCustomer::class.java)
scenario.givenChronicle()
    .events(customerId, CustomerRegistered("Existing"))

val result = scenario.execute(RegisterCustomer(customerId, "Ada"))

result.shouldSucceed()
scenario.chronicle()
    .shouldHaveAppendedEvent(customerId, CustomerRegistered::class.java)
scenario.chronicle().shouldHaveAppendedEvents(1)
```

Given events establish ordered event-source history and are excluded from `appendedEvents`. Arrange deterministic append rejection with `constraintViolation(...)` or `concurrencyViolation(...)`, then assert the machine-readable result with `shouldHaveConstraintViolation(...)` or `shouldHaveConcurrencyViolation(...)`. A rejected append does not appear in `appendedEvents`.

## Test with Java

Use closeable blocking scenarios for ordinary JUnit tests:

```java
CommandScenario<CreateTask> configured = new CommandScenario<>(module, CreateTask.class)
    .addService(TaskRepository.class, repository);

try (BlockingCommandScenario<CreateTask> scenario =
         new BlockingCommandScenario<>(configured)) {
    TaskCreated response = scenario.execute(new CreateTask("Try Arc"))
        .shouldSucceed()
        .shouldHaveResponse(TaskCreated.class);
}
```

Read-model pins are configured on the same scenario object before the bridge is constructed:

```java
CommandScenario<RenameTask> configured = new CommandScenario<>(module, RenameTask.class)
    .withReadModelForKey(TaskView.class, "task-1", new TaskView("task-1", "Current title", 7L));
```

For Chronicle scenarios, Java uses the generated `ChronicleCommandScenarios` static methods for Kotlin extension functions and the same builders and assertions:

```java
ChronicleCommandScenario chronicle = ChronicleCommandScenarios.chronicle(configured);
chronicle.given().events(customerId, new CustomerRegistered("Existing"));

try (BlockingCommandScenario<RegisterCustomer> scenario =
         new BlockingCommandScenario<>(configured)) {
    scenario.execute(new RegisterCustomer(customerId, "Ada")).shouldSucceed();
}
chronicle.shouldHaveAppendedEvent(customerId, CustomerRegistered.class);
```

For nonblocking tests, construct `AsyncCommandScenario`, `AsyncQueryScenario`, or `AsyncObservableQueryScenario` with a caller-owned bounded `CoroutineScope`. Their methods return `CompletionStage`; canceling the future cancels its child coroutine.

## Use manual artifacts only for framework tests

Scenario constructors also accept manual `CommandHandler` and `QueryPerformer` instances. Application tests should select generated artifacts from an `ArcArtifactModule` so KSP wiring remains under test.
