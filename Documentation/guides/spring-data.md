---
title: Use Spring Data read models
description: Inject JPA and MongoDB repositories into model-bound queries, translate Arc paging, and use command transactions.
---

## Add an integration

Choose the store used by the application:

```kotlin
// JPA
dependencies {
    implementation("io.cratis:arc-spring-data-jpa:<version>")
}
```

```kotlin
// MongoDB
dependencies {
    implementation("io.cratis:arc-spring-data-mongodb:<version>")
}
```

Both modules include the Arc Spring Boot starter and the corresponding Spring Data starter. Spring Boot continues to own the datasource, entity manager, Mongo client, repository discovery, and their standard configuration.

## Inject a repository into a query

Spring Data repositories are ordinary Spring services. Mark a model-bound query dependency with `@FromServices`; Arc's generated performer resolves the repository from the current application context.

```kotlin
@Entity
@ReadModel
data class TaskView(@Id val id: String = "", val title: String = "") {
    companion object {
        @JvmStatic
        fun all(@FromServices tasks: TaskViewRepository): List<TaskView> = tasks.findAll()
    }
}

interface TaskViewRepository : JpaRepository<TaskView, String>
```

The same pattern works with `MongoRepository`, repository fragments, and application query services. An application bean replaces an auto-configured adapter bean of the same integration contract.

## Consume paging and sorting

Arc carries zero-based paging and one sort field in `QueryRequest`. A model-bound query can declare the exact non-null Spring Data Commons `Pageable` and `Sort` types. Generated performers create both values directly from the captured query request; they do not use request scope, thread-local state, or a request-scoped bean.

```kotlin
@JvmStatic
fun all(
    pageable: Pageable,
    sort: Sort,
    @FromServices tasks: TaskViewRepository
): Page<TaskView> {
    check(pageable.sort == sort)
    return tasks.findAll(pageable)
}
```

A page size of zero becomes `Pageable.unpaged(sort)`. A blank sort field becomes `Sort.unsorted()`. Exact Spring Data `Page<TaskView>` returns are normalized by the generated performer before Arc renderers run, preserving `content`, zero-based page number, and the repository's pre-page `totalElements`; an unpaged result reports Arc page size zero, matching the compatibility adapters.

`Pageable` marks paging and sorting capabilities; `Sort` marks sorting capability. A query using either host adapter must return exact `Page<T>` so Arc cannot page or sort an already provider-shaped collection again. Client parameters cannot use the reserved names `page`, `pageSize`, `sortBy`, or `sortDirection`. These explicit generated descriptor flags control TypeScript and OpenAPI paging and sorting surfaces. Host-adapter parameters remain in declaration-order metadata for invocation and validation indexes, but Spring binding, Jakarta model-graph validation, TypeScript, OpenAPI, and endpoint introspection expose only client parameters.

The existing `JpaQueryRequestAdapter`, `JpaQueryPageAdapter`, `MongoQueryRequestAdapter`, and `MongoQueryPageAdapter` APIs remain available for manually implemented performers and compatibility. They use the same Spring Data Commons semantics as generated performers.

## Resolve current state for a command

The JPA and MongoDB integrations contribute storage-neutral `CanResolveReadModelForCommand` providers. A generated handler can therefore request its current read model directly; Arc uses the command's generated key and captured tenant context before invoking the handler:

```kotlin
@Command
data class RenameTask(@CommandKey val id: String, val title: String) {
    fun handle(current: TaskView) {
        // current came from the owning persistence provider using id.
    }
}
```

Only exact mapped types carrying `@ReadModel` are claimed. JPA uses `DECLARED` ownership because an entity mapping explicitly selects that store. MongoDB uses `FALLBACK`, so a declaring JPA, Chronicle, or application provider wins. Equal-strength claims fail startup rather than selecting a store by bean order. The supplied command key must already be an instance of the persistence identifier type; contextual providers do not recompute or coerce it. Registry-owned read-model parameters are resolved before ordinary Spring services, so a bean of the model class cannot bypass tenant or key selection. For an owned type, a missing row supplies `null` to Kotlin `T?` and `Optional.empty()` to an ordinary Java `Optional<T>`. A missing command key or missing required owned row produces one `dependencyUnavailable` validation result and HTTP 400. Resolver/storage failures and missing ordinary Spring services remain exceptions. Replacing Arc's `ReadModelForCommandResolverRegistry` bean is an expert override: the application then owns equivalent arbitration and tenancy guarantees.

The historical `JpaCommandReadModelResolver` and `MongoCommandReadModelResolver` APIs remain for fixed-store compatibility. Auto-configuration publishes them only with a verified fixed store and optional tenancy. They recompute a key without `CommandContext` and must not be used for tenant routing.

### Route JPA by tenant

Optional, single-unit applications receive a fixed `JpaPersistenceUnitResolver`. Tenant-routed applications provide one resolver that reports its exact type union and returns a `JpaPersistenceUnit` certified for the requested `tenantId` and `tenantNamespace`. The unit pairs its `EntityManagerFactory` with an optional matching `JpaTransactionManager`; Arc verifies both the certificate and mapped type before lookup. With `cratis.arc.tenancy.required=true`, mapped and resolver-owned JPA read-model claims must match, and the contextual provider must enforce the exact application resolver.

### Route MongoDB by tenant

A fixed `MongoOperationsResolver` serves only commands and observations without a tenant; supplying a tenant fails rather than reading and relabeling the default database. Tenant-routed applications provide one `TenantAwareMongoOperationsResolver`; each lookup returns `TenantMongoOperations`, which certifies the exact tenant identifier together with its isolated `MongoOperations`. Unknown tenants must throw, and Arc rejects mismatched certificates without retrying a default database. Required tenancy validates that command lookup, snapshots, and change streams use adapters for that exact resolver, with no unrelated plain resolver.

## Enroll commands in transactions

Imperative Spring transactions are disabled by default because JPA and MongoDB transaction managers bind resources to a thread while Arc command handlers may suspend and resume on another worker. Fixed-store applications can explicitly opt in:

```yaml
cratis:
  arc:
    spring-data:
      jpa:
        command-transactions-enabled: true
      mongodb:
        command-transactions-enabled: true
```

Opt-in succeeds only for a verified fixed store with an identity-aligned transaction manager. Dynamic tenant resolvers never receive a fixed transaction scope. Even when enabled, command code must not suspend or execute persistence work on another thread; the scope is imperative, not coroutine-safe. The scopes commit only a successful final `CommandResult` and roll back failures when completion remains on the opening thread.

There is no distributed transaction across JPA, MongoDB, and Chronicle. Chronicle now completes after the local scopes, so a JPA or MongoDB completion failure prevents the append. Partial outcomes remain possible in the other direction: MongoDB may commit before JPA fails, and both local stores may commit before Chronicle fails or has an indeterminate external outcome. Imperative coroutine affinity and cross-store reconciliation remain explicit limitations.

## Return observable storage snapshots

Both Spring Data integrations publish injectable query services whose methods return Kotlin `Flow`. A Flow is cold by default: it reads the initial snapshot when collected, owns its subscription, and closes it on cancellation. `observeShared` is the explicit shared alternative and replays one complete snapshot while subscribers exist. Java callers can use the demand-aware `observePublisher` methods or callback overloads, whose returned `AutoCloseable` cancels collection.

### Observe MongoDB

Inject `MongoObservableQuery` into a model-bound query. `observe`, `observeSingle`, and `observeById` re-run the Spring Data query after insert, update, replace, delete, or invalidate notifications:

```kotlin
@JvmStatic
fun observeTasks(
    @FromServices queries: MongoObservableQuery
): Flow<List<TaskView>> = queries.observe(TaskView::class.java)

@JvmStatic
fun observeTask(
    id: String,
    @FromServices queries: MongoObservableQuery
): Flow<TaskView> = queries.observeById(TaskView::class.java, id)
```

`MongoChangeStreamWatcher` is the change-stream SPI. The default `ReconnectingMongoChangeStreamWatcher` uses `SpringDataMongoChangeStreamSource`, resumes from the last token after transient failures, applies capped exponential backoff, and closes its cursor when the collector is canceled. Cursor operations run on `Dispatchers.IO` without a hidden `flowOn` channel. `MongoObservationOptions.bufferCapacity` is exact: zero keeps the producer and collector in rendezvous, while a positive value permits only that many queued changes. MongoDB change streams require a replica set or sharded cluster.

A custom `MongoOperationsResolver` can select operations from the captured `tenantId`; it must not consult thread-local request state. Pass the tenant explicitly to the observation method. The default resolver uses the application's single `MongoOperations` bean. A `Query` supplies filtering, and `observeById` additionally narrows change notifications by document key. Updates and replacements always trigger a fresh snapshot, so a document that stops matching a filter is removed correctly.

### Observe JPA

Inject `JpaObservableQuery` and return `observe`, `observeList`, `observeSingle`, or `observeById` from the read-model query. The default list query uses the mapped JPA entity name; `JpaSnapshotQuery` provides a Java-friendly customization seam for predicates, ordering, and fetch joins.

```kotlin
@JvmStatic
fun observeTasks(
    @FromServices queries: JpaObservableQuery
): Flow<List<TaskView>> = queries.observe(TaskView::class.java)
```

JPA has no portable database change stream. `TransactionAwareDatabaseChangeNotifier` is therefore an explicit in-process publisher: call its `DatabaseChangePublisher.publish(TaskView::class.java, tenantId)` contract from the write side. Notifications are coalesced per transaction, discarded on rollback, and emitted only after commit. `JpaObservationOptions.bufferCapacity` must be positive and bounds pending invalidations; when it is full, the oldest invalidation is replaced because every notification causes a complete snapshot read. Snapshot delivery itself uses a rendezvous handoff rather than `flowOn`'s implicit buffer. The Flow performs its initial snapshot immediately, then debounce-coalesces committed changes into bounded replacement snapshots.

Applications needing cross-process notifications should replace the `DatabaseChangeNotifier` bean with a database-native implementation and expose a corresponding `DatabaseChangePublisher` where local writes also need publishing. Arc does not silently poll either store; polling must be an application-owned, explicitly configured notifier.

## Current public-seam boundary

The integrations deliberately use public Arc and Spring contracts. Contextual command read-model parameters, deterministic ownership, generated `QueryRequest`/`QueryContext` injection, exact Spring Data Commons `Pageable`/`Sort` parameters, and exact `Page<T>` response normalization are implemented. The store-specific request and page adapters remain compatibility utilities rather than request-scoped beans.

Repository injection remains ordinary Spring dependency injection and is not automatically tenant-routed by Arc. JPA observable snapshots still use their configured entity-manager factory; tenant labels on notifications are not storage isolation. Mongo observable paths use `MongoOperationsResolver`, including the certified adapter when a tenant-aware resolver is supplied. Imperative transaction scopes remain explicit fixed-store opt-ins and are not coroutine-safe.
