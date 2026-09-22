---
title: HTTP contract - JVM conformance notes
description: Spring Boot specifics behind Arc's shared HTTP wire contract - paired conformance evidence, correlation filter registration, development provider endpoints, and bounded runtime behavior.
---

:::note[The contract itself lives on the shared page]
Routes, headers, correlation semantics, the QUERY body, observable query transport,
tenant resolution, authentication and introspection, the command and query result
envelopes, HTTP statuses, the identity contract, and validation result values are the
same protocol on every Arc backend. They are documented once, in the
[Arc HTTP contract](/arc/http-contract/), which also records
[where the C# and JVM implementations differ](/arc/http-contract/#where-the-implementations-differ).

This page records only what is specific to the JVM host.
:::

## Paired HTTP conformance evidence

The explicit `:ContractTests:httpConformanceTest` gate exercises the real generated Kotlin and Java
Spring Boot task-board samples alongside a repository-authored ASP.NET Core task board using published
`Cratis.Arc` 22.14.0. It requires JDK 17, pinned .NET SDK 10.0.400 and runtime 10.0.11, and an explicit
lifecycle-owned output location; it is not part of ordinary `build` or `check`. Run instructions live
in `ContractTests/HttpConformance/README.md`.

Nine cases run independently on each host: initial empty query, two distinct typed create responses,
GET and RFC QUERY identifier binding, enumerable snapshots, successful validation without execution,
completion response/state, malformed command JSON without mutation, and unknown-route status. The
harness checks complete two-task snapshots, success flags and correlation UUIDs, QUERY `no-store`,
and malformed-command validation classification without parser details. It preserves raw responses;
it does not normalize them into claimed byte equality.

This is selected task-board HTTP evidence, not a guarantee that all declarations or features match.
The Java array and .NET plain enumerable queries do not count rows like the Kotlin iterable query,
so their paging totals remain outside the common assertions. Framework-generated 404 bodies differ;
only their status is compared. Authentication/authorization rejection, custom validation-rule parity,
null/default handling, temporal precision, paging requests, streaming and database behavior remain
outside this fixture. See the [parity reference](parity.md).

## Correlation filter registration and ordering

The shared contract states what a correlation identifier means and how an inbound value is
treated. On the JVM the Spring Boot starter additionally registers a servlet filter on `/*`,
so the identifier is established for every request reaching the host, whether Arc owns the
route or not.

- The filter is ordered ahead of Spring Security's filter chain and ahead of the Arc
  authentication filter, so both observe the identifier the request will carry.
- The effective value is presented downstream as the configured request header, so an Arc
  endpoint and an ordinary `@RestController` on the same host observe the same value even
  when the client sent no header.
- Servlet-thread code reads the identifier with `ArcCorrelation.of(request)`. The underlying
  servlet request attribute name is `ArcCorrelation.ATTRIBUTE`, and the attribute survives an
  asynchronous dispatch.
- While the filter chain runs, the identifier is published to SLF4J MDC under
  `ArcCorrelation.LOGGING_KEY`, which is `arc.correlation_id`, and any value the host had there
  is restored afterwards. That binding belongs to the servlet thread: a suspending Arc handler
  resumes on another thread and takes its correlation identifier from `CommandContext` or
  `QueryContext` instead.
- Set `cratis.arc.correlation-enabled=false` to leave correlation entirely to the application,
  or define a bean named `arcCorrelationFilterRegistration` to replace the registration.

## Jackson and unknown fields

Arc never configures Jackson's `FAIL_ON_UNKNOWN_PROPERTIES`, so what happens to a field the
target does not declare depends on which reader sees it.

| Surface | Reader | An undeclared field |
| --- | --- | --- |
| QUERY body envelope | Arc's own field-set check | 400 with `malformedRequest` |
| GET and QUERY client arguments | Arc's own argument matching | 400 with `malformedRequest` |
| Command body, including `/validate` | The application `ObjectMapper` bean | Accepted and dropped |
| Anything read through `ArcObjectMapper.create()` | A bare Jackson mapper | Rejected |

Arc validates the QUERY envelope's field set itself, so that rejection holds no matter how the
mapper is configured. A command body is handed straight to the injected Jackson 3 `ObjectMapper`.
The Spring Boot starter contributes `ArcJacksonModule` and the `arcJacksonCustomizer` builder
customizer, which set the naming strategy, null inclusion, named floating-point values, and
date/duration text - and neither touches unknown-property handling. Conventional MVC controllers
and generated Arc endpoints therefore share one mapper and wire policy, and a hosted application
inherits Spring Boot's relaxed default for command bodies.

Jackson 3 mappers are immutable. `ArcObjectMapper.configure(mapper)` returns a configured copy and
never changes the supplied instance; an application that contributes its own Spring mapper remains
responsible for constructing that bean with the Arc configuration it wants.
`ArcObjectMapper.create()` builds a bare mapper and keeps Jackson's own strict default, so an
undeclared field raises `UnrecognizedPropertyException`. Arc uses that mapper only for values it
produced itself - the generated artifact manifest, change-set comparison, and the `CommandScenario`
and `QueryScenario` harnesses - never to read a client request.

## JVM argument binding

`UUID`, `LocalDate`, and `LocalTime` arguments use scalar UUID, date, and time strings in both GET
parameters and QUERY JSON. `Duration` JSON values are ISO-8601 strings because Arc disables
`WRITE_DURATIONS_AS_TIMESTAMPS` in both Core and Spring. A component object is not an alternative
server shape. Arc accepts and emits `LocalTime` values with up to seven fractional digits for
100 ns compatibility; deserialization rejects eight or nine fractional digits with the safe
malformed-query response, and serialization rejects values finer than 100 ns rather than rounding
or truncating them.

A missing Kotlin client parameter with a declared default remains absent so invocation evaluates
that default. A present value is always converted, and explicit `null` is present rather than
omission.

Generated GET clients serialize their `Guid`, `DateOnly`, and `TimeOnly` values to those scalar
strings. This server binding is distinct from the pinned shared TypeScript client's explicit
QUERY-body problem: the generated client passes `DateOnly` and `TimeOnly` component objects to
native `JSON.stringify` because those classes lack `toJSON()`, rather than invoking their typed
scalar serializer. Prefer GET until upstream serialization uses the typed serializer or `toJSON()`.
`Guid` has `toJSON()` and is unaffected.

## Observable transport lifecycle on the JVM

Default observable emission guards reconstruct
[bounded per-dispatch argument copies](../guides/queries.mdx#bound-emission-guard-arguments).
Unsupported or uncopyable guarded arguments terminate with the existing unauthorized result before
any guard executes; this is behavioral tightening, not a wire-schema change. No guards means no
argument-copy validation. Query opening and result-data ownership are unchanged.

When a direct producer completes, fails during opening, or emits an unauthorized terminal result,
the transport stops accepting frames and cancels heartbeats, then writes already accepted frames in
order before closing. Connection capacity and health remain registered until those writes finish.
Final draining uses `cratis.arc.request-timeout` (30 seconds by default) as the asynchronous
request-completion budget, measured from producer completion; values below one millisecond use one
millisecond rather than permitting an unlimited drain. The separate SSE connection-lifetime timeout
still applies and can abort earlier.

Disconnect, transport error, outbound overflow, application shutdown, or expiry of that drain budget
aborts instead: queued frames are discarded, upstream work is cancelled, and connection capacity and
health are released once. WebSocket outbound overflow retains close code 1013 rather than being
replaced by normal closure. Lifecycle calls do not wait for blocking writes or native container
closure, and native close is requested outside lifecycle locks. This bounds logical drain time, not
writer-thread termination or peer acknowledgement: cancellation cannot interrupt arbitrary blocking
servlet/socket I/O, and an in-progress write or native close may outlive the budget under the
container's own I/O timeouts.

If an opening finishes after its subscription has already been replaced or unsubscribed, both hubs
discard its opening failure instead of emitting `Error` or `Unauthorized`. Current-operation failures
retain their terminal envelopes and revisions. This ownership check does not retract already queued
frames or make socket writes atomic with subscription changes.

Both hubs defensively capture the subscription's original string-or-null arguments. They validate
those arguments before reserving subscription state, then bind the captured input again against the
same query metadata on the asynchronous runner. This preserves declared scalar and boxed-array types,
omitted Kotlin defaults, and explicit nulls; it is not arbitrary JVM object cloning. Application
converters therefore run twice and must be deterministic and independent of request-thread state.
Canonical `Collection` and `MutableCollection` metadata can be bound by the host, but generated
collection-interface query parameters remain rejected by KSP; manual metadata support does not
establish generated collection support. Explicit validation severity is retained, and an omitted
severity uses the subscribed query's `TreatWarningsAsErrors` default.

Spring WebSocket remains optional: when it is absent or disabled, HTTP and SSE continue to work and
clients use their normal reconnect backoff.

## Development provider endpoints

`/.cratis/users` and `/.cratis/tenants` aggregate all ordered coroutine provider beans and Java
`AsyncUsersProvider`/`AsyncTenantsProvider` beans. The first item for each identifier wins, so the
result is deduplicated by principal ID and tenant ID respectively. Empty provider sets return `[]`.
Requests use Arc's asynchronous timeout and cancellation handling; provider failures return a
redacted 500 JSON error and are logged server-side. These development discovery routes allow
anonymous access.

## Identity cookie secure policy

The shared contract describes the `.cratis-identity` cookie. On the JVM its `Secure` attribute
follows `identity-cookie-secure-policy`: `always`, `never`, or `auto`. `auto` secures it for HTTPS
requests and, by default, for all non-development profiles.

## Bounded runtime behavior

The Spring host admits at most `coroutine-parallelism + coroutine-queue-capacity` Arc operations
without waiting for a slot; exhaustion fails closed with 503 and `Retry-After`. Command and QUERY
bodies are counted while streaming and rejected with 413 at the configured
`maximum-request-body-bytes` even when `Content-Length` is absent. Request, identity,
authentication, development-provider, and observable waits use bounded timeouts.

Observable transports separately bound physical connections, subscriptions per multiplexed
connection, outbound frames, inbound WebSocket message bytes, connection lifetime, and retained
subscription revision tombstones. Connection capacity exhaustion returns 503 with `Retry-After`;
SSE subscription exhaustion returns 429. Overflow closes or rejects work instead of growing memory
without limit. Command execution-scope completion is also independently timeout-bounded so one
broken scope cannot prevent best-effort completion of earlier scopes.

## Validation and exception conversion on the JVM

Command exceptions implementing
[`ValidationFailure`](../guides/commands.md#convert-application-exceptions-to-command-validation) with
a usable payload become validation results at the default command pipeline's ordinary exception
boundaries, including context/filter failures on `/validate`. Pure blocking validation returns 400
with empty exception messages and stack trace; ordinary exceptions remain 500 with the existing
production/development redaction policy. Cancellation never becomes validation feedback. Parser,
admission, transport timeout, and query exception behavior are unchanged; severity filtering remains
[stage-specific](../guides/commands.md#convert-application-exceptions-to-command-validation).

[Direct concept exclusions](../guides/commands.md#exclude-a-direct-concept-rule-edge) affect only
concept rules on a matching owner/member edge. They do not bypass Jakarta or model validation or
change any HTTP envelope/status policy. Command execution, command validation, one-shot queries, and
observable HTTP snapshots still reject blocking feedback from remaining rules. This is not a claim
that streaming openings return HTTP 400.

Server-only [`ModelValidator` rules](../guides/commands.md#reuse-model-validation) use the same
envelope and severity policy. Streaming opening failures retain their existing envelopes: direct SSE
sends failed `QueryResult` data on the established HTTP stream, direct WebSocket sends a `Data` frame,
and hubs send `Error` messages. Relative model paths are prefixed with the bound node path.
Registration adds no manifest, client-validation, or OpenAPI schema fields. Only supplied query
arguments are validated, not omitted defaults, infrastructure, or observable result data.
