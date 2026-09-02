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

When Spring has a Jakarta `Validator` bean, Arc automatically validates caller-supplied query arguments before invoking either a one-shot or observable performer. It evaluates Kotlin method-parameter constraints, follows `@Valid` typed argument graphs from Kotlin or Java, preserves array/list/map member paths, deduplicates equivalent violations, and terminates cyclic graphs safely. Jakarta executable validation requires a complete positional argument array, but an omitted Kotlin default has no value until invocation. Arc therefore ignores executable-constraint results for that omitted slot rather than validating a fabricated `null`; supplied arguments and their object graphs are still validated, while the default expression and any value it creates execute at the model boundary. Jakarta executable validation requires an invocation receiver, while model-bound Java query methods are static; KSP therefore rejects constraints declared directly on static Java query parameters with `ARCKSP0301`. Put those rules in a `QueryValidator` or on an `@Valid` argument model instead of accepting a server-side validation bypass. `@FromServices`, `QueryRequest`, and `QueryContext` parameters are infrastructure-owned: GET and QUERY binders ignore payload values with those names, while observable subscription arguments reject them as non-client fields. No binder requires them as caller arguments or applies client validation to them. Host-neutral `QueryValidator` rules and `ConceptValidator` rules still compose through `DefaultQueryValidationFilter`; see [Create and validate commands](commands.md) for the concept-validator wiring boundary.

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

- HTTP GET or enabled RFC QUERY returns `202` with a not-ready `QueryResult` unless `waitForFirstResult=true` is supplied. `waitForFirstResultTimeout=<seconds>` can shorten the configured wait limit. Observable QUERY uses the standard `{arguments,paging,sorting}` body and `Cache-Control: no-store`; disabling QUERY returns 405 with `Allow: GET`.
- SSE is selected with `Accept: text/event-stream` and sends each result as exactly `data: {QueryResult}\n\n`.
- WebSocket is selected by upgrading the query route. Data frames use `{ "type": "Data", "data": {QueryResult} }`; `Ping` and `Pong` frames carry millisecond timestamps.

Multiple subscriptions can share one physical connection through `/.cratis/queries/ws`, or through `/.cratis/queries/sse` with POST requests to `/.cratis/queries/sse/subscribe` and `/.cratis/queries/sse/unsubscribe`. These fixed transport routes permit anonymous connections while still capturing a principal when credentials succeed. Every subscription then passes through its query's normal authorization pipeline, so a protected query terminates as `Unauthorized` without disturbing authorized subscriptions on the same connection. Hub messages use the PascalCase `Connected`, `Subscribe`, `Unsubscribe`, `QueryResult`, `Unauthorized`, `Error`, `Ping`, and `Pong` types. `Connected` advertises `keepAliveIntervalMs` and `supportsSubscriptionRevisions`; result and terminal frames echo the client's `queryId` and safe-integer `revision`.

Set `transferMode` to `full` for snapshots or `delta` for change sets after the first snapshot. Authorization, caller arguments, principal, and tenant are captured independently for every accepted subscription. An unauthorized result is terminal.

## Extend result processing

Register `QueryRendererFor<T>` beans to transform supported result values and paging before they leave the query pipeline. Renderers run in ascending `order()` and retain registration order for ties. Kotlin code can read the renderer's `type` and `order` property views, while Java implementations retain `queryType()` and `order()`. Arc includes `QueryableQueryRenderer`, which applies in-memory paging and sorting to `Iterable` results; large or provider-backed queries should return `QueryPage` or use a store-specific renderer so filtering remains in the database.

Register `InterceptReadModel<T>` beans for ordered, per-model interception after rendering. Kotlin code can use the interceptor's `type` and `order` property views; Java retains `readModelType()` and `order()`. Both renderer and interceptor chains apply to one-shot and observable results. Blocking Java stores can implement the corresponding blocking convenience interfaces; asynchronous contracts use `CompletionStage`.

`QueryPaging` and `QuerySorting` are Kotlin data classes, with `UNPAGED` and `UNSORTED` reusable values available as Java static fields. Kotlin query-result consumers can use `fold`, `getOrThrow`, `onSuccess`, and `validationOrNull`. Kotlin property views expose renderer/interceptor types and order, resolver ownership, and enum wire values without changing their Java methods.

Java Core extensions do not require coroutine types at implementation boundaries. `BlockingQueryFilter`/`AsyncQueryFilter`, authorization-filter, validator, and manual performer adapters bridge synchronous or `CompletionStage` code; manual performers returning JDK `Flow.Publisher` are adapted to Kotlin `Flow`. `JavaAsyncScope.observableQueries(...)` exposes a cancellable `CompletionStage` open operation and a demand-aware JDK publisher, while `queryHealth(...)` exposes health snapshots the same way.

Register `GuardObservableQueryEmission` beans when authorization or another condition must be rechecked for every emission. A denied emission produces an unauthorized terminal result and cancels the subscription. The guard context includes the performer, principal, tenant ID, tenant namespace, arguments, and current data.

## Inspect observable health

`QueryHealthTracker` records physical connections, activity timestamps, pong timestamps, and active subscriptions. It exposes both a snapshot and an observable `Flow<QueryHealth>`. The Spring host publishes the current snapshot at `GET /.cratis/queries/health` and RFC QUERY on the same route. The transport removes health entries when connections close or fail.
