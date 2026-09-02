---
title: Integrate commands and read models with Chronicle
description: Append transactional event responses, apply concurrency scopes, resolve read models, execute reactor side effects, and test commands in process.
---

## Keep Arc Core independent

`io.cratis:arc` has no Chronicle dependency. Add the optional integration when commands should append returned events or use Chronicle read models and reactors:

```kotlin
dependencies {
    implementation("io.cratis:arc-chronicle-spring-boot-starter:<version>")
}
```

The Arc starter transitively includes `io.cratis:chronicle-spring-boot-starter` and follows its Spring Boot bean backoff conventions. The current integration uses Chronicle.Kotlin 4.0.0 with Chronicle contracts and kernel 16.44.1. Configure `cratis.chronicle.event-store`; Chronicle then supplies the conventional client and `IEventStore` beans while Arc adds tenant-aware event-store resolution, a staged command scope, response handlers, read-model adapters, and a reactor command-side-effect helper.

## Run the complete Kotlin and Java samples

The optional `Samples:Kotlin:ChronicleSpringBoot` and `Samples:Java:ChronicleSpringBoot` applications use only public starters and generated model-bound endpoints. Each includes:

- `CreateTask`, whose returned `TaskCreated` event is appended and omitted from the client response;
- `RenameTask`, which receives the tenant-local `TaskView`, records its previous title, and applies the caller's exact observed event-log position;
- a Chronicle reducer that materializes `TaskView` from event context without duplicating the event-source ID in event payloads;
- generated GET and RFC QUERY endpoints at `/api/tasks/by-id` and `/api/tasks`;
- strict generated TypeScript contracts for Kotlin and Java.

Run either sample against Chronicle 16.44.1 and include `x-cratis-tenant-id` on every request. The explicit compatibility gate starts the pinned development image and exercises both applications:

```shell
./gradlew :ContractTests:chronicleRealKernelTest --no-configuration-cache
```

Override `-PchronicleKernelImage=<pinned-image>` only for a deliberate compatibility run. The default image is `cratis/chronicle:16.44.1-development` pinned to OCI index digest `sha256:3e0216892632f87e5386649cf8c1a189573cf82999abf14b7f6031863a6e545f`. Docker absence fails this explicit task; normal unit tests do not start a kernel.

## Return an event

A plain Chronicle event response needs a stable command key backed by `String`, `UUID`, a number, or an Arc/Chronicle concept wrapping one of those values.

```kotlin
import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.chronicle.events.EventType

@EventType
data class TaskCreated(val title: String)

@Command
data class CreateTask(@CommandKey val id: String, val title: String) {
    fun handle(): TaskCreated = TaskCreated(title)
}
```

Without a usable `@CommandKey`, Arc returns an error validation result with `reason: "rule"` and `reasonDetail: "commandKey"`; it does not guess an event-source identifier.

Return a non-empty collection or array of Chronicle `@EventType` values to target the command-key stream. Return `EventForEventSourceId` values to route events explicitly. `CommandResponseValues`, Kotlin `Pair`/`Triple`, and `ArcOneOf` may combine supported response values in declaration order, but one collection must not mix plain events with routed events.

## Commit one staged Chronicle unit of work

The Spring integration starts one `ChronicleCommandTransaction` for the outermost command-execution root before filters. Nested commands executed in the same structured coroutine, or with `CommandExecutionOptions.nested(parentContext)`, receive distinct frame tokens but enroll in that same root. Child completion never appends. If any child or the root fails or is canceled—even when an outer handler ignores the child result—the root becomes rollback-only and discards every staged event.

A successful root commits the selected event store's ordered events with exactly one `appendMany` call and the root correlation identifier. One root may use only one event-store object and namespace; attempts to switch stores, join with another correlation/namespace, enroll after sealing, or handle transactionally without active state fail closed. The public response-handler constructors without a transaction remain explicitly nontransactional and may append immediately.

There are no nested savepoints. Arc seals the root before any execution scope completes, rejects late joins, and fails when an unawaited child remains live. Cancellation first supplies a failed cleanup result to every begun scope, performs reverse cleanup, and only then rethrows the original cancellation.

Chronicle begins before the opt-in JPA and MongoDB scopes. Reverse completion is MongoDB, JPA, then Chronicle, so a local completion failure prevents Chronicle append. This is a commit barrier, not a distributed transaction: MongoDB may commit before JPA fails, and both local stores may commit before Chronicle fails or has an indeterminate external outcome. Another Chronicle store or external system is never part of this batch.

Constraint violations become validation results with reason `constraintViolation`; the constraint ID is `reasonDetail`, Chronicle details are retained as state, and a `propertyName` detail becomes a camel-cased member. Concurrency failures use `concurrencyViolation` and include expected and actual sequence numbers. Other append errors become command exception messages.

## Attach exact concurrency scopes

Use `EventsWithConcurrencyScopes` when a cross-source batch needs explicit Chronicle concurrency rules:

```kotlin
import io.cratis.arc.chronicle.eventsWithConcurrencyScopes

fun handle(): EventsWithConcurrencyScopes = eventsWithConcurrencyScopes {
    event("account-42", FundsWithdrawn(100))
    event("ledger-2025", LedgerEntryAdded("account-42", 100))
    concurrencyScope("account-42") {
        withEventSourceId()
    }
}
```

The builder requires at least one event, validates event-source labels, preserves declaration order, rejects conflicting scopes for the same label, and supplies the exact scope map to Chronicle's atomic append. Java uses `EventsWithConcurrencyScopes.builder()`, fluent `event(...)`, and either a concrete `ConcurrencyScope` or the `Consumer<ConcurrencyScopeBuilder>` overload.

## Resolve tenant event stores

The command or query context carries the tenant namespace captured by the host. For a non-null namespace, the integration resolves an event store through `TenantEventStoreResolver` and verifies that the returned store has that exact namespace. Register a `TenantEventStoreProvider` bean to supply tenant stores; the auto-configured resolver falls back to the default `IEventStore` only when its namespace matches. A null tenant uses the default store.

A missing, mismatched, or failing tenant store fails closed without appending or releasing data. An application-provided resolver replaces the default composition.

## Use Chronicle read models

When `ChronicleOptions` lists read-model artifacts, the integration declares those exact types to Arc's `ReadModelForCommandResolverRegistry`. A generated command handler can then request a current read model as an unannotated parameter:

```kotlin
@Command
data class Withdraw(
    @CommandKey val accountId: String,
    val amount: Int
) {
    fun handle(balance: AccountBalance): FundsWithdrawn {
        require(balance.available >= amount)
        return FundsWithdrawn(amount)
    }
}
```

Resolution uses the generated command key and the captured tenant store. A value produced by `provide` still has precedence. Once the ownership registry claims a read-model type, it resolves before ordinary Spring services so a model bean cannot bypass tenant/key selection. A missing model leaves the dependency unresolved; an invalid key or unavailable/mismatched store fails the command rather than falling through to another store. Chronicle claims declared ownership only for its discovered types, so Arc can arbitrate them against custom or Spring Data resolvers deterministically.

The same artifact list configures `ChronicleReadModelInterceptor`. Before one-shot or observable query data leaves the pipeline, each matching read-model value is passed through Chronicle's `readModels.release(...)` operation for compliance-protected data. Collection items are intercepted individually, and the tenant store must match the captured query namespace.

## Execute reactor command side effects

`ChronicleCommandSideEffectHandler` explicitly hands one command or a non-empty nested command aggregate from a Chronicle reactor to Arc's real `CommandPipeline`. It accepts `CommandResponseValues`, `ArcOneOf`, Kotlin tuples, iterables, and arrays only when every leaf is a registered command. Commands run sequentially in declaration order and stop at the first failed result. These reactor side-effect calls are independent top-level command executions; earlier commands remain durable if a later one fails. `executeAsync(...)` exposes the same contract as a Java `CompletionStage`.

Pass the reactor type and Chronicle `EventContext` to the handler. By default, Arc preserves the causing identity and correlation/namespace context but grants no roles, including when the causing identity is Chronicle's system identity. Annotate a reactor type with `@ExecuteCommandsAsSystem(roles = [...])` only when the automation deliberately needs those exact roles; blank roles are rejected.

This helper is an explicit side-effect boundary. It does not make every value returned by a reactor an Arc command and does not bypass command validation or authorization.

## Test without a Chronicle kernel

Add `io.cratis:arc-testing` in the test configuration alongside the Chronicle integration. The Chronicle module registers a `CommandScenarioExtender` through `ServiceLoader`, so `CommandScenario` receives an in-memory event log automatically:

```kotlin
val scenario = CommandScenario(module, RegisterCustomer::class.java)
scenario.givenChronicle()
    .events(customerId, CustomerRegistered("Existing"))

val result = scenario.execute(RegisterCustomer(customerId, "Ada"))

result.shouldSucceed()
scenario.chronicle()
    .shouldHaveAppendedEvent(customerId, CustomerRegistered::class.java)
```

Given events establish ordered history and are excluded from appended-event assertions. Builders can arrange deterministic constraint and concurrency violations; assertions inspect the same machine-readable `ValidationResult` contract used at runtime. Java uses the generated `ChronicleCommandScenarios` static bridge. See [Test commands and queries in process](testing.md) for the complete Kotlin and Java examples.
