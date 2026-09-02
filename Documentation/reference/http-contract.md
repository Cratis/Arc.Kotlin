---
title: HTTP contract reference
description: Exact Arc servlet routes, methods, headers, envelopes, statuses, identity endpoints, and validation wire values.
---

## Routes and methods

| Endpoint | Method | Contract |
| --- | --- | --- |
| Command route | `POST` | JSON command body; returns `CommandResult`. |
| `<command-route>/validate` | `POST` | Runs authorization and validation without the handler. |
| Query route | `GET` | Arguments in query parameters; returns `QueryResult`. |
| Query route | `QUERY` | Structured JSON request; enabled by default for one-shot queries; returns `Cache-Control: no-store`. |
| Observable query route | `GET` | HTTP snapshot, direct SSE when accepting `text/event-stream`, or a direct WebSocket upgrade. |
| `/.cratis/queries/ws` | WebSocket | Multiplexed observable-query hub. |
| `/.cratis/queries/sse` | `GET` | Multiplexed SSE hub; starts with a `Connected` message carrying the connection ID. |
| `/.cratis/queries/sse/subscribe` | `POST` | Adds or revision-replaces a subscription on a principal-bound SSE connection. |
| `/.cratis/queries/sse/unsubscribe` | `POST` | Cancels a subscription or records its revision tombstone. |
| `/.cratis/commands` | `GET` | Anonymous deterministic command introspection metadata. |
| `/.cratis/queries` | `GET` | Anonymous deterministic query introspection metadata. |
| `/.cratis/queries/health` | `GET`, `QUERY` | Current physical observable connection and subscription health. |
| `/.cratis/me` | `GET` | Registered only with exactly one identity details provider. |
| `/.cratis/identity-details/schema` | `GET` | Always registered; returns `{}` without a provider. |
| `/.cratis/users` | `GET` | Anonymous development-user discovery; returns an ordered, principal-ID-deduplicated array or `[]`. |
| `/.cratis/tenants` | `GET` | Anonymous development-tenant discovery; returns an ordered, tenant-ID-deduplicated array or `[]`. |

Unsupported methods return 405 with an `Allow` header. Conventional routes use the configured prefix, skipped package segments, kebab case, and artifact name. Explicit query `@Path` values are preserved exactly.

## Request headers

| Header | Behavior |
| --- | --- |
| `X-Correlation-ID` | Default correlation header, configurable with `cratis.arc.correlation-header`. A valid UUID is reused; a missing or invalid value is replaced. Command and one-shot/observable query responses echo the effective UUID under the configured name. |
| `X-Allowed-Severity` | Maximum nonblocking severity as a case-insensitive name or numeric wire value. Invalid input produces `malformedRequest`. |
| `x-cratis-tenant-id` | Default tenant header; `cratis.arc.tenant-header` remains an alias/default for `cratis.arc.tenancy.header-name`. |

## QUERY body

The body may contain only these fields:

```json
{
  "arguments": {"name":"Ada"},
  "paging": {"page":0,"pageSize":25},
  "sorting": {"field":"name","direction":"ascending"}
}
```

`paging` and `sorting` may be omitted. GET and QUERY match client argument names case-insensitively and reject keys that collide after case folding as `malformedRequest`. Unknown fields remain invalid. A missing Kotlin client parameter with a declared default remains absent so invocation evaluates that default. A present value is always converted, and explicit `null` is present rather than omission. Page values must be nonnegative. Directions accept `asc`, `ascending`, `desc`, or `descending`, case-insensitively. GET uses `page`, `pageSize`, `sortBy`, and `sortDirection` as reserved parameters. `UUID`, `LocalDate`, and `LocalTime` arguments use scalar UUID, date, and time strings in both GET parameters and QUERY JSON. `Duration` JSON values are ISO-8601 strings because Arc disables `WRITE_DURATIONS_AS_TIMESTAMPS` in both Core and Spring. A component object is not an alternative server shape. Arc accepts and emits `LocalTime` values with up to seven fractional digits for 100 ns compatibility. Deserialization rejects eight or nine fractional digits with the safe malformed-query response, and serialization rejects values finer than 100 ns rather than rounding or truncating them.

Generated GET clients serialize their `Guid`, `DateOnly`, and `TimeOnly` values to those scalar strings. This server binding is distinct from the pinned shared TypeScript client's explicit QUERY-body problem: the generated client passes `DateOnly` and `TimeOnly` component objects to native `JSON.stringify` because those classes lack `toJSON()`, rather than invoking their typed scalar serializer. Prefer GET until upstream serialization uses the typed serializer or `toJSON()`. `Guid` has `toJSON()` and is unaffected.

## Observable query transport

Without `waitForFirstResult=true`, an observable query HTTP snapshot returns a not-ready `QueryResult` with 202. With it, the host waits up to the smaller of `waitForFirstResultTimeout=<seconds>`, the configured observable wait limit, and the request timeout. When enabled, RFC QUERY is registered on the same observable route, consumes the standard body, and returns `Cache-Control: no-store`; when disabled it returns 405 with `Allow: GET`. SSE and WebSocket upgrades remain GET-only.

Direct SSE writes exactly `data: {QueryResult}\n\n`. Direct WebSocket data uses a `Data` frame with the `QueryResult` in `data`; `Ping` and `Pong` carry `timestamp`.

Hub JSON uses exact PascalCase types: `Connected`, `Subscribe`, `Unsubscribe`, `QueryResult`, `Unauthorized`, `Error`, `Ping`, and `Pong`. `queryId` and a positive JavaScript-safe `revision` are echoed on result and terminal messages. `Connected` includes `keepAliveIntervalMs` and `supportsSubscriptionRevisions: true`. Subscription payloads accept `queryName`, string `arguments`, paging, sorting, and `transferMode` (`full` or `delta`).

Fixed multiplexed connection, subscribe, and unsubscribe routes allow anonymous transport access and retain a successfully authenticated handshake principal when credentials are present. Each subscription is authorized independently by the existing query pipeline; a protected anonymous subscription emits terminal `Unauthorized` without terminating unrelated authorized subscriptions. Connection capacity exhaustion returns 503 with `Retry-After`; SSE subscription exhaustion returns 429. Unknown or caller-mismatched SSE connection IDs return 404. Spring WebSocket remains optional: when it is absent or disabled, HTTP and SSE continue to work and clients use their normal reconnect backoff.

## Tenant resolution

The Spring host creates an explicit tenant context from case-insensitive request headers, each query parameter's first value, the server host, and captured principal claims. The configured resolver chain runs once at each HTTP request, SSE subscription, or WebSocket handshake. Its result is passed as both `tenantId` and `tenantNamespace` for commands and every query transport. Identity detail requests resolve the same context and include a resolved tenant as the configured tenant claim when it was not already present. No transport uses thread-local tenant state.

When `tenancy.required=true`, unresolved requests return 400 (or a failed WebSocket handshake). Authenticated callers with tenant membership claims receive a generic 403 when selecting another tenant. Fixed and development values are used only when those strategies are explicitly configured.

## Authentication and introspection

When `AuthenticationHandler` or Java `AsyncAuthenticationHandler` beans are registered, Arc authenticates protected Arc routes before dispatch. Handlers execute in Spring order. An authenticated result supplies the request principal, anonymous results allow later handlers to try, and failed results are aggregated internally but exposed only as the generic 401 response. `@AllowAnonymous` artifacts may proceed without an authenticated result. Introspection, users, tenants, and identity schema routes are literal anonymous endpoints.

`/.cratis/commands` returns route, type, documentation, payload schema, authorization, properties, and validation metadata. `/.cratis/queries` additionally returns the fully qualified query name, argument schema, transport, paging support, and HTTP preference. Query parameter metadata reports `hasDefault`, and the argument schema excludes defaulted parameters from `required`; neither endpoint exposes a Kotlin default expression or an invented value. UUID and supported textual terminal `java.time` values (`LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`, `OffsetDateTime`, `ZonedDateTime`, `OffsetTime`, `Duration`, and `Period`) appear as scalar `string` schemas, including collection elements, rather than object schemas. Registry-version caches refresh only when generated artifact registries change.

## Development provider endpoints

`/.cratis/users` and `/.cratis/tenants` aggregate all ordered coroutine provider beans and Java `AsyncUsersProvider`/`AsyncTenantsProvider` beans. The first item for each identifier wins. Empty provider sets return `[]`. Requests use Arc's asynchronous timeout and cancellation handling; provider failures return a redacted 500 JSON error and are logged server-side. These development discovery routes allow anonymous access.

## Command result envelope

| Field | Type | Notes |
| --- | --- | --- |
| `correlationId` | UUID | Always present. |
| `isAuthorized` | Boolean | Authorization outcome. |
| `validationResults` | Array | Always present. |
| `exceptionMessages` | Array | Always present; production redaction removes details. |
| `exceptionStackTrace` | String | Empty when redacted. |
| `authorizationFailureReason` | String | Empty when absent. |
| `isValid` | Boolean | True only when validation results are empty. |
| `hasExceptions` | Boolean | True when exception messages exist. |
| `isSuccess` | Boolean | Authorized, valid, and exception-free. |
| `response` | Any | Omitted when null or failed. |

## Query result envelope

`QueryResult` includes `correlationId`, optional `data`, `isReady`, `isAuthorized`, `validationResults`, `exceptionMessages`, `exceptionStackTrace`, `paging`, optional `changeSet`, `isValid`, `hasExceptions`, and `isSuccess`. `paging` contains `page`, `size`, `totalItems`, and calculated `totalPages`.

## HTTP statuses

| Condition | Status |
| --- | --- |
| Successful command or query | 200 |
| Query not ready | 202 |
| Validation, malformed request, missing command key, or missing required owned command read model | 400 |
| Authentication failure | 401 |
| Command or query authorization failure | 403 |
| Request body exceeds `maximum-request-body-bytes` | 413 |
| Subscription capacity exhausted | 429 |
| Request or connection admission exhausted | 503 with `Retry-After` |
| Pipeline or host exception | 500 |
| Unsupported method | 405 |

## Identity contract

`/.cratis/me` returns 401 for an unauthenticated principal, 403 when the details provider rejects the caller, and 200 with `id`, `name`, `isAuthenticated`, `isAuthorized`, `roles`, and application-specific `details` on success.

A successful response sets `.cratis-identity` to Base64-encoded response JSON. The cookie is client-readable (`HttpOnly=false`), `SameSite=Lax`, and `Path=/`. Its `Secure` attribute follows `identity-cookie-secure-policy`: `always`, `never`, or `auto`. `auto` secures it for HTTPS requests and, by default, for all non-development profiles.

## Validation result

Each result has numeric `severity`, `message`, `members`, optional `state`, `reason`, and optional `reasonDetail`.

| Severity | Wire value |
| --- | --- |
| `Unknown` | 0 |
| `Information` | 1 |
| `Warning` | 2 |
| `Error` | 3 |

Reason strings are open for future additions. Current values are `rule`, `concurrencyViolation`, `constraintViolation`, `validatorFailed`, `dependencyUnavailable`, and `malformedRequest`.

## Bounded runtime behavior

The Spring host admits at most `coroutine-parallelism + coroutine-queue-capacity` Arc operations without waiting for a slot; exhaustion fails closed with 503. Command and QUERY bodies are counted while streaming and rejected at the configured byte limit even when `Content-Length` is absent. Request, identity, authentication, development-provider, and observable waits use bounded timeouts.

Observable transports separately bound physical connections, subscriptions per multiplexed connection, outbound frames, inbound WebSocket message bytes, connection lifetime, and retained subscription revision tombstones. Overflow closes or rejects work instead of growing memory without limit. Command execution-scope completion is also independently timeout-bounded so one broken scope cannot prevent best-effort completion of earlier scopes.
