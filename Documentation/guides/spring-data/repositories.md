---
title: Repositories in queries and commands
description: Translate Arc paging and sorting, resolve current state, reach tenant-bound storage, and enroll a command in a transaction.
---

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

## Inject tenant-bound MongoDB access

When a tenant-aware resolver is configured, Arc auto-configures a `TenantContextMongoAccess` bean that handlers
can inject. It resolves the correct tenant's `MongoOperations` at the moment each method is called — the tenant
is never captured at construction time. This mirrors the `IMongoDatabase` and `IMongoCollection<T>` scoped registrations
in Arc .NET's `ServiceCollectionExtensions.AddCratisMongoDB` (`Source/DotNET/MongoDB/ServiceCollectionExtensions.cs`).

### Kotlin coroutine call paths

Call `operations()` inside a `withTenant` scope or any coroutine whose context carries a `TenantCoroutineContext`;
the ambient tenant is read on every call:

```kotlin
@Command
data class ArchiveTask(@CommandKey val id: String) {
    suspend fun handle(
        @FromServices access: TenantContextMongoAccess
    ) {
        // operations() reads currentTenant() from the coroutine context — never from construction time.
        val ops = access.operations()
        ops.remove(Query.query(Criteria.where("_id").`is`(id)), TaskDocument::class.java)
    }
}
```

For direct native driver access, `collection()` returns a `MongoCollection<T>` named by the `NamingPolicy`:

```kotlin
suspend fun handle(
    @FromServices access: TenantContextMongoAccess
) {
    val col = access.collection(TaskDocument::class.java)
    col.deleteOne(Filters.eq("_id", id))
}
```

The collection name comes from `NamingPolicy.getReadModelName()` (kernel-aligned English pluralization), not from
Spring Data's `@Document` annotation. For Spring Data-mapped types, prefer `operations()` and its type-safe CRUD
methods, which respect `@Document` and the configured object mapping.

### Java call paths

Java command handlers that run on a blocking thread use `TenantContextBridge.withTenant` to establish the tenant,
then call `operationsForCurrentTenant()`:

```java
public class ArchiveTaskHandler implements BlockingCommandHandler {
    private final TenantContextMongoAccess access;

    public ArchiveTaskHandler(TenantContextMongoAccess access) {
        this.access = access;
    }

    @Override
    public void handle(CommandContext context) {
        TenantContextBridge.withTenant(TenantId.of(context.getTenantId()), () -> {
            MongoOperations ops = access.operationsForCurrentTenant();
            ops.remove(Query.query(Criteria.where("_id").is(context.getCommandKey())), TaskDocument.class);
        });
    }
}
```

Alternatively, pass the tenant explicitly using the overload that bypasses the ambient context:

```java
MongoOperations ops = access.operations(TenantId.of(tenantId));
```

This overload is callable from both Kotlin and Java without any context setup.

### No-tenant fallback

When no tenant is present in the current context and no fallback tenant is configured, all resolution methods
throw `IllegalStateException`. Construct `TenantContextMongoAccess` with an explicit `fallbackTenantId` when
the application has a default tenant for non-tenanted code paths:

```kotlin
// Application bean override — use the workspace default tenant when no tenant is active.
@Bean
fun arcTenantContextMongoAccess(
    resolver: TenantAwareMongoOperationsResolver,
    namingPolicy: NamingPolicy
): TenantContextMongoAccess = TenantContextMongoAccess(resolver, namingPolicy, TenantId.DEFAULT)
```

```java
// Java equivalent.
@Bean
public TenantContextMongoAccess arcTenantContextMongoAccess(
    TenantAwareMongoOperationsResolver resolver,
    NamingPolicy namingPolicy
) {
    return new TenantContextMongoAccess(resolver, namingPolicy, TenantId.DEFAULT);
}
```

The auto-configured bean uses no fallback (throw on missing tenant). Override it as above when a fallback is needed.

### Real-server coverage

`TenantContextMongoAccessTests` and `JavaTenantContextMongoAccessTests` verify per-call tenant resolution and
isolation against the `mongo-java-server` 1.47.0 `MemoryBackend` emulator. The `mongoReplicaSetTest` gate
additionally proves the following against a real pinned MongoDB 8.2 single-node replica set
(`mongo:8.2@sha256:e0ce8c35124d4a9f9785532d1f268f39e9728ffa1cb38f46fa482436424c4bd3`):

- `TenantContextMongoAccess` routes both coroutine (`withTenant`) and Java blocking (`TenantContextBridge`) calls
  to genuinely isolated real MongoDB databases for two different tenants.
- The `DefaultNamingPolicy` kernel-aligned correction (`Person` → `People`) routes writes to the correct physical
  collection on a real server; collection `Persons` is never created.
- A change-stream cursor opened against a real replica set receives an `INSERT` event. Change streams require
  replica-set mode and cannot be verified against the MemoryBackend emulator.
- Kotlin data class and Java record concept IDs (UUID binary subtype 4, string, int64) persist with correct BSON
  types and round-trip on a real server; null `@Field(ALWAYS)` fields appear as BSON null; malformed stored
  scalars fail visibly through the reading converter.

The gate does not cover replica-set failover, TLS, transactions through `TenantContextMongoAccess`, or
deterministic resume/reconnect under cursor interruption. Those limitations remain.

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
