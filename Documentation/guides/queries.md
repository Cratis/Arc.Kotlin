---
title: Expose one-shot and observable queries
description: Add model-bound read-model queries with GET, RFC QUERY, HTTP snapshots, SSE, and WebSocket hosting.
---

## Add a query to a read model

Annotate the result model with `@ReadModel`. Put query operations on its Kotlin companion object with `@JvmStatic`, or use Java static methods. Mark dependency parameters with `@FromServices`; caller arguments remain unannotated. A query may also declare exact non-null `QueryRequest` and `QueryContext` parameters in any positions. KSP generates a reflection-free `QueryPerformer` that supplies service, request, and context parameters from the execution context while preserving declaration order.

```kotlin
@ReadModel
@AllowAnonymous
data class TaskView(val id: String, val title: String) {
    companion object {
        @JvmStatic
        @Path("/api/tasks/by-id")
        suspend fun byId(id: String, @FromServices repository: TaskRepository): TaskView? = repository.byId(id)

        @JvmStatic
        @Path("/api/tasks")
        fun all(@FromServices repository: TaskRepository): List<TaskView> = repository.all()
    }
}
```

Java query methods may return a value or `CompletionStage<T>`. Generated performers await asynchronous results inside Arc's request coroutine context. KSP ignores only non-query Kotlin companion and Java static helper methods whose return does not contain the enclosing read-model shape. Custom instance methods remain invalid on an `@ReadModel` and stop compilation. Adding `@Path`, `@QueryHttpMethod`, or `@QueryTransport` explicitly declares such a method as a query, so an invalid annotated return still fails compilation. Public, non-generic, non-overloaded query requirements are unchanged.

Kotlin client parameters may declare defaults. GET, QUERY, and observable subscriptions preserve omission by leaving that argument out of `QueryRequest.arguments`, so the generated performer executes the Kotlin default expression. A supplied value still goes through normal conversion and validation; explicit JSON or subscription `null` is a supplied value, not omission, and is accepted only by a nullable parameter. Metadata records only that a default exists. It never publishes the expression or invents a literal value: generated TypeScript fields and call arguments are optional, OpenAPI marks the parameter non-required, and introspection removes it from the argument schema's `required` array. Client validation skips rule results for an omitted defaulted field but still validates supplied overrides. KSP generates direct named-argument presence branches, supports at most six defaulted client parameters (64 masks), and fails larger shapes with `ARCKSP0209` rather than using reflection. Java query parameters have no Arc default feature, and overloaded query methods remain unsupported.

When Spring has a Jakarta `Validator` bean, Arc automatically validates caller-supplied query arguments before invoking either a one-shot or observable performer. It evaluates Kotlin method-parameter constraints, follows `@Valid` typed argument graphs from Kotlin or Java, preserves array/list/map member paths, deduplicates equivalent violations, and terminates cyclic graphs safely. Jakarta executable validation requires a complete positional argument array, but an omitted Kotlin default has no value until invocation. Arc therefore ignores executable-constraint results for that omitted slot rather than validating a fabricated `null`; supplied arguments and their object graphs are still validated, while the default expression and any value it creates execute at the model boundary. Jakarta executable validation requires an invocation receiver, while model-bound Java query methods are static; KSP therefore rejects constraints declared directly on static Java query parameters with `ARCKSP0301`. Put those rules in a `QueryValidator` or on an `@Valid` argument model instead of accepting a server-side validation bypass. `@FromServices`, `QueryRequest`, and `QueryContext` parameters are infrastructure-owned: GET and QUERY binders ignore payload values with those names, while observable subscription arguments reject them as non-client fields. No binder requires them as caller arguments or applies client validation to them. Host-neutral `QueryValidator`, `ConceptValidator`, and exact-runtime-class `ModelValidator` rules compose through `DefaultQueryValidationFilter`; see [reusable model validation](commands.md#reuse-model-validation) for ordering, paths, Java adapters, and Spring wiring. Model and concept traversal visits only supplied arguments before one-shot invocation or observable opening, not result data or every emission. Omitted defaults and infrastructure remain excluded; these imperative rules are server-only and do not introduce query exception conversion.

[Direct concept exclusions](commands.md#exclude-a-direct-concept-rule-edge) apply within supplied argument graphs. They match the exact runtime owner and direct concept member, not the argument name or a dotted path. Root concept arguments remain validated; each supplied argument has independent identity tracking, and explicit null or an omitted default creates no concept node. Jakarta and model rules remain active on excluded edges.

## Choose a route and method

`@Path` preserves the supplied query path exactly. Without it, Arc derives a route from `cratis.arc.endpoints` settings. One-shot queries accept GET. They also accept RFC QUERY when `enable-query-http-method` is true.

Use GET for scalar values:

```bash
curl -sS 'http://localhost:8080/api/tasks/by-id?id=123'
```

Use QUERY for structured arguments, paging, and sorting:

```bash
curl -sS -X QUERY http://localhost:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"arguments":{},"paging":{"page":0,"pageSize":25},"sorting":{"field":"title","direction":"ascending"}}'
```

GET reserves `page`, `pageSize`, `sortBy`, and `sortDirection`. Client argument names are matched case-insensitively for GET and QUERY; two supplied names that differ only by case are malformed. QUERY accepts only `arguments`, `paging`, and `sorting`, and rejects unknown fields. QUERY responses include `Cache-Control: no-store`.

Arc accepts and emits `LocalTime` values with up to seven fractional digits for 100 ns compatibility. Deserialization rejects eight or nine fractional digits as malformed, and serialization rejects values finer than 100 ns rather than rounding or truncating them. Generated GET clients serialize `DateOnly`, `TimeOnly`, and `Guid` query arguments as scalar strings. Prefer GET when a generated query has `DateOnly` or `TimeOnly` parameters: this server binding is distinct from the pinned shared `@cratis/arc` client's explicit QUERY-body problem, which passes their component objects to native `JSON.stringify` because they have no `toJSON()` instead of invoking the typed scalar serializer. That client limitation does not change the JVM endpoint contract. Prefer GET until upstream serialization uses the typed serializer or `toJSON()`. `Guid` is unaffected because it has `toJSON()`.

## Set proxy preferences

`@QueryHttpMethod(GET|QUERY|AUTO)` controls generated proxy preference. Put it on `@ReadModel` to establish a default and override that default on individual methods. Queries returning Kotlin `Flow<T>` / `Flow<List<T>>` or JDK `Flow.Publisher<T>` / `Publisher<List<T>>` are generated as `OBSERVABLE` performers; a generated observable QUERY preference has a matching server snapshot route when QUERY is enabled.

## Consume an observable query

The same generated query route supports three direct transports:

- HTTP GET or enabled RFC QUERY returns `200` with the current value when the query returns a `StateFlow`, because that source already holds one. A cold `Flow` or JDK `Flow.Publisher` has nothing to serve yet and returns `202` with a not-ready `QueryResult` unless `waitForFirstResult=true` is supplied. `waitForFirstResultTimeout=<seconds>` can shorten the configured wait limit. Observable QUERY uses the standard `{arguments,paging,sorting}` body and `Cache-Control: no-store`; disabling QUERY returns 405 with `Allow: GET`.
- SSE is selected with `Accept: text/event-stream` and sends each result as exactly `data: {QueryResult}\n\n`.
- WebSocket is selected by upgrading the query route. Data frames use `{ "type": "Data", "data": {QueryResult} }`; `Ping` and `Pong` frames carry millisecond timestamps.

Multiple subscriptions can share one physical connection through `/.cratis/queries/ws`, or through `/.cratis/queries/sse` with POST requests to `/.cratis/queries/sse/subscribe` and `/.cratis/queries/sse/unsubscribe`. These fixed transport routes permit anonymous connections while still capturing a principal when credentials succeed. Every subscription then passes through its query's normal authorization pipeline, so a protected query terminates as `Unauthorized` without disturbing authorized subscriptions on the same connection. Hub messages use the PascalCase `Connected`, `Subscribe`, `Unsubscribe`, `QueryResult`, `Unauthorized`, `Error`, `Ping`, and `Pong` types. `Connected` advertises `keepAliveIntervalMs` and `supportsSubscriptionRevisions`; result and terminal frames echo the client's `queryId` and safe-integer `revision`.

Set `transferMode` to `full` for snapshots without a change set, or `delta` for the full first snapshot followed by change sets only. Omitting `transferMode` keeps the legacy behavior: every result carries both the full snapshot and a `changeSet`, with the first one listing every item as added. Authorization, caller arguments, principal, and tenant are captured independently for every accepted subscription. An unauthorized result is terminal.

With explicit `transferMode=delta`, Arc prefers a stable generated or conventional item identity. When no identity accessor exists, or an extracted key is null or duplicated, Arc falls back to exact serialized JSON identity. The fallback reports additions and removals only: changing any field is one removal plus one addition, never a replacement. It compares set membership, matching Arc .NET, so changing only the number of identical duplicate values produces no delta. Use stable, unique item identities when replacements, duplicate counts, or lower serialization cost matter. Change sets do not encode position changes, so do not rely on delta updates to reproduce list reordering.

## Extend result processing

Register `QueryRendererFor<T>` beans to transform supported result values and paging before they leave the query pipeline. Configured renderers match the **original** performer value, run in ascending `order()`, and retain registration order for ties. Each receives the preceding stage's `current` result. Kotlin code can read the renderer's `type` and `order` property views, while Java implementations retain `queryType()` and `order()`.

A matching application renderer chain owns its data and paging. `DefaultQueryRenderers` uses its automatic
`QueryableQueryRenderer` fallback only when **no configured renderer matches** the original value. This
prevents restored filtered rows, lost projections, premature provider enumeration, and overwritten
provider totals. Even a matching identity/no-op renderer owns its output; automatic in-memory paging
is not silently appended to that chain.

To filter first and then apply standard in-memory sorting/paging, explicitly include
`QueryableQueryRenderer()` after the filter in the configured chain. In Spring, register it as a bean
alongside the transforming renderer and make ordering explicit; its order is zero. Arc sorts primarily
by each renderer's `order()`; Spring's `orderedStream()` supplies the input order retained for ties,
not an override of that method. To place the built-in at another order, wrap/delegate it through the
existing renderer interfaces. Avoid duplicate in-memory stages. This explicit stage
processes `current.data`, never the original rows, and preserves null/non-iterable current projections.
Do not add another in-memory paging stage after a renderer that already owns a provider page. Return
`QueryPage` from the performer or use a store-specific renderer without that additional stage so
filtering and paging remain owned by the database.

This is a behavioral migration for existing renderer contributions: an application renderer no longer
gets implicit iterable processing before or after it. Direct callers of `QueryableQueryRenderer` must
initialize `QueryRendererResult.data` with the values to process; null now means a null projection,
not permission to recover the original query. Later interceptors or serialization may still enumerate
the **current output**; ownership does not make an unchanged provider cursor lazy end-to-end.
Original-array and performer-returned `QueryPage`/`QueryResult` dispatch boundaries are unchanged.

The default in-memory sorter reads public instance properties only. It honors public Kotlin getters
(including properties with private setters), Java record accessors, field-backed public JavaBean getters,
and public Java instance fields. It does not bypass a getter to read private storage or expose arbitrary
zero-argument methods. Kotlin visibility is retained for inherited Kotlin declarations on Java classes.
Every non-null runtime row type is checked before comparisons, including singleton results; inaccessible
and unknown keys use the same failure path, with no sorted payload. Getter cancellation and fatal errors
propagate rather than becoming ordinary query failures.

This is a visibility boundary, **not a per-field authorization policy**. A public property remains public
even if JSON annotations omit it. Return a suitably restricted DTO or supply a custom renderer when a
query needs a narrower sort-key allowlist. Empty/all-null results provide no runtime type to inspect.
Provider-owned `QueryPage` results and the documented original-array boundary are unchanged.

Register `InterceptReadModel<T>` beans for ordered, per-model interception after rendering. Kotlin code can use the interceptor's `type` and `order` property views; Java retains `readModelType()` and `order()`. Both renderer and interceptor chains apply to one-shot and observable results. Blocking Java stores can implement the corresponding blocking convenience interfaces; asynchronous contracts use `CompletionStage`.

`QueryPaging` and `QuerySorting` are Kotlin data classes, with `UNPAGED` and `UNSORTED` reusable values available as Java static fields. Kotlin query-result consumers can use `fold`, `getOrThrow`, `onSuccess`, and `validationOrNull`. Kotlin property views expose renderer/interceptor types and order, resolver ownership, and enum wire values without changing their Java methods.

Java Core extensions do not require coroutine types at implementation boundaries. `BlockingQueryFilter`/`AsyncQueryFilter`, authorization-filter, validator, and manual performer adapters bridge synchronous or `CompletionStage` code; manual performers returning JDK `Flow.Publisher` are adapted to Kotlin `Flow`. `JavaAsyncScope.observableQueries(...)` exposes a cancellable `CompletionStage` open operation and a demand-aware JDK publisher, while `queryHealth(...)` exposes health snapshots the same way.

Register `GuardObservableQueryEmission` beans when authorization or another condition must be rechecked for every emission. A denied emission produces an unauthorized terminal result and cancels the subscription. A suppressed emission is withheld from the subscriber and leaves the subscription open. The guard context includes the performer, principal, tenant ID, tenant namespace, arguments, current data, and whether this would be the first emission delivered to the subscriber. Suppressed emissions are not deliveries, so `isFirstEmission` stays true until one is delivered, and `delta` transfer sends that first delivered emission as a full snapshot.

### Bound emission-guard arguments

`DefaultObservableQueryEmissionGuards` captures supported argument values once at the start of each guard dispatch, then reconstructs independent values for every guard before invoking the first guard. Guard mutations to these values do not reach another guard, the performer, or a later emission. This is **per-dispatch isolation**, not an opening-time freeze: `QueryRequest` copies only its outer map, and caller or performer mutations before the next dispatch can affect its next capture. Do not concurrently mutate a value while capture itself is reading it; capture is not an atomic transaction over application memory. The principal, services, and `context.data` retain their existing ownership and are not cloned.

The default supported argument graph is deliberately bounded:

- Null values and absent map entries remain distinct. Map/list iteration order and scalar runtime types are preserved.
- Immutable scalars are accepted by **exact class**, not by a `Number` or temporal interface: `String`, boxed Boolean/Byte/Short/Integer/Long/Float/Double/Character, `BigInteger`, `BigDecimal`, `UUID`, and `java.time` `Instant`, `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, `OffsetTime`, `ZonedDateTime`, `Duration`, `Period`, `Year`, `YearMonth`, and `MonthDay`.
- Ordinary enums and `ArcEnum` constants retain singleton identity only after a structural instance-state check. Every nonstatic field from the constant-specific subclass through its declaring enum must be final and either primitive or declared as an exact **final** immutable scalar class from the list above. `java.lang.Enum`'s own name and ordinal are known immutable. This accepts plain constants and final integer wire values, but rejects nonfinal fields, arrays, collections, enum-valued fields, unknown reference types, and even `BigInteger`/`BigDecimal` fields because those declared classes are not final. Synthetic, transient, and ignored instance fields are not exempt. The check does not read or mutate fields, call application getters, allocate enum instances, or clone constants. It does not guarantee isolation of static/global application state or arbitrary method side effects.
- Primitive arrays and object arrays retain their component classes, including boxed, UUID, checked enum (also constant-specific subclass), concept, and nested arrays. Lists reconstruct as `ArrayList`; string-key maps reconstruct as `LinkedHashMap`. Container implementation identity, aliases, and extra implementation state are not preserved. An array whose component cannot accept the reconstructed value (for example a nonempty `LinkedList[]`) is unsupported.
- Concrete final, nonlocal, nonanonymous, nongeneric `ConceptAs` classes may carry one non-null supported scalar. Across their class hierarchy they must have exactly one instance field, whose declared type (boxed if primitive) equals that scalar's runtime class; additional instance state, including ignored or transient state, is unsupported. Concepts must serialize as non-null JSON scalars and reconstruct with exactly the same concept type and scalar type/value. The default Arc mapper additionally requires an accessible public single-value constructor. This includes supported Kotlin scalar wrappers and public Java records; it is not general model or polymorphic graph cloning.

Enums whose instance state fails the check, sets, arbitrary model objects, custom numeric/temporal implementations, cyclic graphs, and unsupported concepts fail closed before any guard runs rather than falling back to the original value. Shared acyclic inputs are independently expanded, not preserved as aliases. The maximum capture depth is 64 (outer argument map is depth 0), and at most 10,000 value nodes may be captured, counting the outer map, each container/concept/scalar/null occurrence, and repeated aliases. Reconstruction permits at most 100,000 node occurrences in total across every guard copy **plus one discarded preflight copy**. Map keys are not separate nodes. Exceeding either budget denies; nothing is truncated. Container sizes are checked before bulk allocation, and repeated subgraphs consume the same budget to bound recursive amplification.

The no-argument and iterable-only constructors retain their source and binary call shapes and use `ArcObjectMapper.create()`. The additive `(guards, mapper)` constructor accepts the authoritative application mapper. Spring supplies its mapper bean when present (and retains the default when none exists); an application mapper is not silently reconfigured. `ObservableQueryScenario` uses an Arc mapper with the scenario's actual derived-type registry. Supplying a mapper does **not** enable polymorphic argument support or bypass discriminator validation.

Custom concept codecs must be deterministic, preserve the scalar contract, and return fresh independent instances without retaining or later mutating guard-owned values. Runtime checks reject returned originals, repeated instances within one dispatch (including the discarded preflight copy), scalar/type changes, nonscalar JSON, and round-trip JSON changes. These checks detect obvious cached/shared codecs; they cannot prove purity or independence of arbitrary stateful application code. Such codecs remain the application's responsibility.

Unsupported values and ordinary capture/reconstruction/guard failures deny and terminate before unsafe values reach any guard. All argument copies are prepared before guard one, so even a later copy failure invokes no guards. Guard invocation order, most-restrictive verdict aggregation, and deny short-circuiting are unchanged. Active coroutine cancellation propagates during capture, reconstruction, and awaiting guards; an independently cancelled guard future while its caller remains active still denies. With no guards, the aggregator returns `ALLOW` immediately without inspecting arguments. Synchronous application codecs must remain cooperative; cancellation checks cannot preempt blocking user code.

This is a **behavioral tightening**, not universally backward-compatible behavior: previously accepted guarded argument shapes can now terminate unauthorized. No wire schema or manifest version changes. Applications requiring broader argument ownership semantics must explicitly replace the existing `ObservableQueryEmissionGuards` aggregator and own that contract; there is no new public copy-policy SPI or unsafe fallback.

## Inspect observable health

`QueryHealthTracker` records physical connections, activity timestamps, pong timestamps, and active subscriptions. It exposes both a snapshot and an observable `Flow<QueryHealth>`. The Spring host publishes the current snapshot at `GET /.cratis/queries/health` and RFC QUERY on the same route. The transport removes health entries when connections close or fail.

The snapshot reports connection and subscription identifiers, remote IP addresses, user agents, and user identities, so both methods require an authenticated caller whenever Arc authentication handlers are registered; anonymous callers receive 401. Applications with no `AuthenticationHandler` or `AsyncAuthenticationHandler` bean are unaffected. This is a diagnostics endpoint, not a container liveness probe: use Spring Boot Actuator health groups for liveness and readiness, which Arc never intercepts.
