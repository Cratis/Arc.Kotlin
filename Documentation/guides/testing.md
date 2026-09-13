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

`arc-testing` uses the real command, one-shot query, and observable-query pipelines. It does not start Spring Boot or replace generated behavior with fake handlers. `CommandScenario` and `QueryScenario` enable JSON round trips by default; `ObservableQueryScenario` exercises the pipeline without those opening-argument or result-data JSON round trips. Its default emission guards independently reconstruct [supported arguments per dispatch](queries.md#bound-emission-guard-arguments), using an Arc mapper with the scenario's actual derived-type registry for concrete scalar concepts. That does not enable polymorphic argument cloning; unsupported guarded shapes terminate unauthorized before any guard runs.

`CommandScenario` and `QueryScenario` register the supplied module's `derivedTypes` in a scenario-local Arc JSON registry before reading values. Command properties and nested query data declared as registered interfaces or base classes therefore retain their concrete types through the round trip. Registrations do not leak between scenarios; a manual handler or performer alone contributes no derived-type registrations.

For direct query values and list entries, `QueryScenario` uses the descriptor's declared registered base type rather than reading an annotated derivative as its runtime class. Unknown or missing `_derivedTypeId` values still fail; scenarios do not infer registrations or silently accept unknown identifiers. This is not an arbitrary generic graph-cloning API: erased generic roots, undeclared polymorphic roots, and other container shapes retain the existing runtime-class round-trip limitations. Ordinary model properties use their Jackson-declared types. Serialization failures propagate to the test rather than becoming successful pipeline results.

## Test with Kotlin

```kotlin
val module = TaskApplicationArcArtifactModule()
val result = CommandScenario(module, CreateTask::class.java)
    .addService(TaskRepository::class.java, repository)
    .execute(CreateTask("Try Arc"))

result.shouldSucceed().shouldHaveResponse(TaskCreated::class.java)
```

Select queries with `FullyQualifiedQueryName` and use `QueryScenario<T>`. Add services, validators, policies, filters, renderers, read-model interceptors, a principal, tenant, correlation ID, paging, or sorting through scenario methods. Disable command or one-shot query serialization round trips only when the test intentionally bypasses the wire boundary.

All three scenario classes expose `addModelValidator(ModelValidator<?>)`, `addConceptValidator(ConceptValidator<?>)`, and `addConceptExclusion(ConceptValidationExclusion)`, returning the configured scenario. They compose with existing `addValidator` rules through the real default validation filter, without replacing it. Register Java blocking or asynchronous model adapters on that scenario before constructing its Java bridge. Model rules run on command inputs or supplied query arguments, not services, omitted defaults, or returned data; see [reusable model validation](commands.md#reuse-model-validation). Default command/query JSON round trips may turn a shared source identity into separate model instances, so validation follows the prepared graph's identities. Register [direct concept exclusions](commands.md#exclude-a-direct-concept-rule-edge) before creating the existing blocking or asynchronous Java scenario bridge. An exclusion suppresses only concept rules on that owner/member edge, never model rules or other filters. To prove that an ignored-first shared instance still validates on a required edge, use an additional in-process check with command or query argument serialization disabled; JSON duplication alone cannot prove alias handling.

Command and query results carry matching positive and negative assertions, so a test can pin which stage rejected an operation instead of only that it failed. `shouldBeAuthorized` and `shouldBeUnauthorized` cover authorization, `shouldBeValid` and `shouldBeInvalid` cover validation feedback, and `shouldHaveErrors` and `shouldHaveNoErrors` cover retained exception messages. Chaining `shouldBeAuthorized().shouldBeInvalid()` states that authorization passed and validation rejected the command, which `shouldFail` alone does not.

`CommandScenarioResult.shouldBeInvalid()` requires that validation actually ran. It fails when every validation result has reason `dependencyUnavailable`, because that outcome usually means the scenario forgot to register a read model or another validator dependency and no rule executed. Seed the missing dependency for a rule assertion. When dependency failure is the behavior under test, assert it explicitly with `shouldHaveValidation(reason = ValidationResultReasons.DEPENDENCY_UNAVAILABLE)`; this remains green without pretending a validation rule ran.

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

## Test observable queries

Use `ObservableQueryScenario<T>.collect(maximumEmissions, timeoutMillis)` to collect at most the requested number of emissions through the real observable-query pipeline. One timeout budget covers both opening (including suspending authorization, filters, and performer creation) and collection. Timeout and caller cancellation propagate to the Kotlin caller and cancel cooperative upstream work; they are not successful scenario results.

`maximumEmissions` is a cap, not a required count. A finite stream may complete early or empty, and `shouldSucceed()` alone does not require an emission. Chain `shouldHaveEmissionCount(expected)` whenever the test requires an exact count. Reaching the cap stops upstream collection. Add per-emission guards with `addEmissionGuard`; opening rejection is available through `shouldFail()`, while a terminal guard denial is an unauthorized emission checked with `shouldTerminateUnauthorized()`.

Like command and one-shot query scenarios, the observable scenario derives its default validation threshold from the selected descriptor: `TreatWarningsAsErrors` makes warnings blocking while allowing information. `withAllowedValidationSeverity` explicitly overrides that default, including `null`, which restores error-only blocking. The observable scenario does not currently provide the command/one-shot argument and result-data JSON round trips; collecting values is not a serialization-contract check.

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

For asynchronous tests, construct `AsyncCommandScenario`, `AsyncQueryScenario`, or `AsyncObservableQueryScenario` with a `JavaAsyncScope`. No Kotlin coroutine imports are needed. Command and one-shot query bridges accept a configured scenario, a manual handler/performer, or a module plus the command class/query name. The observable bridge accepts a configured `ObservableQueryScenario<T>`.

```java
ExecutorService executor = Executors.newSingleThreadExecutor();
try (JavaAsyncScope owner = JavaAsyncScope.owningExecutorService(executor)) {
    AsyncCommandScenario<CreateTask> commands =
        new AsyncCommandScenario<>(configured, owner);
    commands.execute(new CreateTask("Try Arc"))
        .toCompletableFuture().get(5, TimeUnit.SECONDS).shouldSucceed();
}
```

Use `JavaAsyncScope.usingExecutor(executor)` to borrow an executor without shutting it down, or `owningExecutorService(executor)` to transfer shutdown responsibility. All three asynchronous scenarios **borrow** the owner: they neither implement `AutoCloseable` nor close it. Close the owner explicitly after the test. Closing it cancels its Core and Testing operations; cancellation cleans up cooperative upstream work, not arbitrary blocking code.

`execute`, `validate`, and `perform` return `CompletionStage`. `AsyncObservableQueryScenario.collectAsync(maximumEmissions)` also returns a `CompletionStage<ObservableQueryScenarioResult<T>>`; overloads accept a timeout (default 5,000 milliseconds), arguments, paging, sorting, and transfer mode (default `FULL`). The same opening-and-collection timeout budget and emission cap apply. Timeout and cancellation cancel the stage rather than returning a successful result. Canceling `toCompletableFuture()` cancels only that child operation, not the owner or siblings. Stages can complete before coroutine cleanup has finished; tests that require cleanup must observe the upstream cleanup signal too.

Supply an executor with nonblocking submission for prompt asynchronous return. Direct or inlining executors can execute on the calling thread; a blocking executor or blocking user operation is not covered by an unconditional concurrency or interruption promise. Existing scenario instances return canceled stages or inert callback handles after owner close without executing user code. Constructing a scenario with an already-closed owner is allowed, but Core facade factories such as `owner.commands(...)` still reject a closed owner synchronously.

The existing `collect(...)` method remains callback/handle-based and returns `ObservableQueryScenarioHandle`; `cancel()` cancels upstream work. Cancellation, including internal timeout, invokes neither callback. A success-callback exception is passed to the failure callback on the same coroutine; a failure-callback exception escapes that coroutine. Do not use callback delivery alone as a timeout completion signal; use `collectAsync` when stage termination is needed.

The existing `CoroutineScope` constructors and Kotlin default calls remain available. Adding owner overloads can make an untyped `null` scope argument ambiguous in Java; a null owner/scope is not supported. For Kotlin integration modules, `JavaAsyncScope.launchStage`, `JavaAsyncScope.launch`, and `CoroutineScope.launchStage` (in `io.cratis.arc.java`, JVM holder `CoroutineCompletionStages`) are supported public Kotlin/JVM SPI. Their `@JvmSynthetic` methods are hidden from Java source, not reflection. The shared stage helper catches ordinary `Exception` failures into the stage without failing an ordinary parent job; other throwables retain coroutine parent-failure semantics. These are JVM-specific bridges, not an Arc .NET parity claim.

## Use manual artifacts only for framework tests

Scenario constructors also accept manual `CommandHandler` and `QueryPerformer` instances. Application tests should select generated artifacts from an `ArcArtifactModule` so KSP wiring remains under test.
