# Arc for Kotlin and Java

Arc.Kotlin is the JVM implementation of Arc for Kotlin and Java applications hosted by Spring Boot. It provides compile-time model-bound commands and queries, generated TypeScript clients, servlet hosting, optional persistence and Chronicle integrations, OpenAPI, and in-process test support. It does not claim complete feature parity with Arc on .NET; the implemented and intentionally unsupported areas are listed in the [parity reference](Documentation/reference/parity.md).

## Workspace

| Project | Published identity | Responsibility |
| --- | --- | --- |
| `:Source` | `io.cratis:arc` | Host-independent command, query, validation, authorization, authentication, identity, tenancy, introspection, result, JSON, and artifact contracts |
| `:CodeGeneration:KSP` | `io.cratis:arc-ksp` | Reflection-free command/query generation, manifests, concept and Jakarta validation metadata, stable `ARCKSP` diagnostics, and a checked ABI baseline |
| `:GradlePlugin` | Gradle plugin `io.cratis.arc` (`io.cratis:arc-gradle-plugin`) | JVM/KSP conventions, one-shot plus observable TypeScript proxy generation, and a checked ABI baseline |
| `:Integrations:SpringBoot` | `io.cratis:arc-spring-boot-starter` | Spring Boot auto-configuration and servlet HTTP, SSE, and optional WebSocket hosting |
| `:Integrations:SpringDataJpa` | `io.cratis:arc-spring-data-jpa` | Spring Data JPA paging, read-model, command transaction, and observable `Flow` adapters |
| `:Integrations:SpringDataMongo` | `io.cratis:arc-spring-data-mongodb` | Spring Data MongoDB paging, read-model, command transaction, and change-stream-backed observable `Flow` adapters |
| `:Integrations:OpenApi` | `io.cratis:arc-openapi-spring-boot-starter` | OpenAPI 3.1 generation and cached document routes |
| `:Integrations:Observability` | `io.cratis:arc-observability-spring-boot-starter` | Micrometer observations and optional OpenTelemetry correlation for Arc execution |
| `:Integrations:Chronicle` | `io.cratis:arc-chronicle-spring-boot-starter` | Optional tenant-aware Chronicle transactions, concurrency, read models, command side effects, and scenario support |
| [`:Testing`](Testing/README.md) | `io.cratis:arc-testing` | Reusable command, query, and observable-query scenarios with Kotlin and Java bridges; Chronicle adds an in-memory scenario extender |
| `:ContractTests` | Unpublished | Kotlin and Java generated-artifact, manifest, validation, and consumer contract fixtures |
| `:Samples:Kotlin:SpringBoot` | Unpublished | Runnable standalone Kotlin Spring Boot application |
| `:Samples:Java:SpringBoot` | Unpublished | Runnable standalone Java Spring Boot application |
| `:Samples:Kotlin:ChronicleSpringBoot` | Unpublished | Runnable tenant-aware Kotlin Arc + Chronicle application |
| `:Samples:Java:ChronicleSpringBoot` | Unpublished | Runnable tenant-aware ordinary-Java Arc + Chronicle application |

`Source` is deliberately independent of Spring Boot and Chronicle. Integrations depend inward on `Source`; samples consume public starters. Spring Boot is the only supported host, JSON uses Jackson, and no Ktor host is included.

## Implemented functionality

- Model-bound commands with regular, `suspend`, and Java `CompletionStage` handlers; model-bound `provide` supports ordered values, `Pair`, `Triple`, `CommandProvidedValues`, control short-circuiting, and `ArcOneOf` alternatives.
- Command responses recursively flatten `Pair`, `Triple`, `ArcOneOf`, and nested `CommandResult` values in declaration order. Source-visible `@HandlesCommandResponseValues` declarations classify custom server-consumed leaves; exactly one remaining client leaf drives the runtime result, TypeScript response type, and OpenAPI schema, while multiple client leaves fail compilation with `ARCKSP0109`.
- One-shot and observable model-bound queries, query validation, renderers, read-model interceptors, observable emission guards, health tracking, paging, sorting, GET, and RFC QUERY.
- Observable HTTP snapshots, direct SSE and WebSocket routes, multiplexed SSE/WebSocket hubs, full/delta transfer, subscription revisions, heartbeats, health reporting, and generated observable TypeScript proxies.
- Validation and authorization pipelines; Spring automatically validates command and typed query argument graphs with Jakarta Bean Validation when a `Validator` is present, while host-neutral `ConceptValidator` support applies reusable concept rules across command and query graphs.
- KSP preserves exactly representable Jakarta and concept rules in runtime metadata and manifests. TypeScript proxies merge concept and owning-member rules, while OpenAPI exposes reusable scalar concept schemas instead of wrapper objects.
- Pluggable coroutine and Java asynchronous authentication, command/query introspection, identity details and schema, development users and tenants, and explicit tenant resolution and access checks without thread-local state.
- Spring Data JPA and MongoDB exact `Pageable`/`Sort` query parameters, exact `Page<T>` normalization, compatibility paging adapters, read-model/transaction integration, cold or shared observable `Flow` snapshots, and demand-aware Java publishers.
- Tenant-aware Chronicle staged transactions, exact concurrency scopes, command-side read-model resolution, query read-model release, reactor-to-command side effects, and in-memory command scenarios. Dedicated Kotlin and ordinary-Java samples return events from model-bound commands, materialize/query Chronicle read models, and expose response-less generated TypeScript commands. A separate Docker-backed compatibility task exercises both generated Spring applications against the pinned Chronicle 16.44.1 kernel.
- Kotlin result, aggregate, service-resolution, and property-view conveniences, plus blocking and `CompletionStage` Java adapters for Core command, query, authorization, validation, observable, and scope contracts.
- OpenAPI 3.1 generation and Micrometer command/query/authentication/identity observations with optional OpenTelemetry correlation.
- Generated proxies map `LocalDate`, `LocalTime`, and `UUID` to `DateOnly`, `TimeOnly`, and `Guid` from `@cratis/fundamentals`; UUID-to-`Guid` is an explicit .NET parity decision. Commands and GET query parameters use scalar strings, returned generated models hydrate into class instances, and OpenAPI remains string/date, string/time, and string/uuid. Arc disables Jackson's `WRITE_DURATIONS_AS_TIMESTAMPS` in Core and Spring, so `Duration` reads and writes ISO-8601 text, generated TypeScript remains `string`, and OpenAPI uses `string`/`duration`. It is not mapped to Fundamentals `TimeSpan` because the Java and C# wire formats differ. Core round-trip tests also establish ISO-8601 text for `Period`, and the focused generator contract maps it to TypeScript `string`; no broader `Period` transport claim is made.
- The generated TypeScript compatibility contract is strict with `verbatimModuleSyntax` and type-only interface imports where appropriate. String-keyed maps with nonnullable string/boolean/character/byte/short/integer entries generate recursive `Record<string, V>` types, nested sequences append `[]`, and map runtime metadata uses `Object`; Jackson and OpenAPI use ordinary recursive JSON objects. Floating-point leaves are rejected because non-finite values do not share one JSON contract. Reserved keys `__proto__`, `prototype`, and `constructor` fail on both server and generated command clients. The runtime harness has five wired unit tests and enforces 25 behavioral runtime tests: 15 general tests in UTC plus five calendar tests in each of separate UTC and `America/Los_Angeles` Node processes. Counts are parsed from TAP, with exact test/pass totals and zero fail, cancelled, skipped, or todo results; Spring process-spawn errors fail cleanly. The temporal/UUID generated-type change is source-breaking for TypeScript consumers. Recursive type metadata intentionally moves manifests to format 5 and adds public JVM shape descriptors while preserving legacy constructor descriptors.
- Stable KSP diagnostics, checked binary-compatibility baselines for published modules including KSP and the Gradle plugin, a proxy differential against a repository-local semantically normalized .NET-derived expected fixture intended for source control, strict TypeScript compilation, and a generated-client runtime E2E gate against the executable Kotlin Spring Boot sample. Exact path and body comparison occurs after CRLF-to-LF and generated-header normalization, the fixture's capture-time namespace and query-name casing transformations, and expected-side .NET import rewrites required by `verbatimModuleSyntax`: `SetCommandValues`/`ClearCommandValues` and query helper types such as `PerformQuery`, `SetSorting`, `SetPage`, `SetPageSize`, and `ChangeSet` become type-only imports. One additional expected-side correction at `Commands/CreateFixtures.ts` changes `Command<ICreateFixtures, FixtureModel>` to `Command<ICreateFixtures, FixtureModel[]>`. Current .NET output already calls `super(FixtureModel, true)` and is enumerable at runtime, so its scalar generic is a known typing defect. The proven .NET-derived `FixtureModel.labelsByCategory` line is already `@field(Object)` plus `Record<string, string>` and receives no dictionary rewrite; it is compared exactly after the documented line-ending/header and import normalization. This proves only that bounded string/string Record fixture, not non-string keys, nullable entries, typed model values, `ValueMap`, or broader dictionary parity. These normalizations never transform JVM output. The temporal/UUID mapping itself is covered by focused generator and contract tests because the current .NET differential fixture contains no `Guid`, `DateOnly`, or `TimeOnly`. Capture-time fixture preparation still needs reproducible tooling.
- Bounded host execution and transport limits, including request admission, body size, timeouts, streaming connection/subscription limits, bounded buffers, and fail-closed overload behavior.

## Build

The build uses the checked-in Gradle 8.13 wrapper and requires JDK 17. Make a JDK 17 installation the active `JAVA_HOME`/`PATH`; no repository-specific absolute JDK path is required.

```shell
java -version
./gradlew build --no-configuration-cache
```

Run the documentation-only gate with:

```shell
./Documentation/verify-markdown.sh
```

Supply a release version with `-Pversion=<version>`; local builds default to `0.0.0-SNAPSHOT`.

## Current limits

Arc.Kotlin does not claim full Arc .NET parity. Controllers and non-Spring hosts are outside its direction. OpenAPI deliberately omits RFC QUERY because OpenAPI Path Items do not define that method. Generated query performers inject exact `QueryRequest`, `QueryContext`, `Pageable`, and `Sort` parameters without exposing them to clients, normalize exact `Page<T>` results, and the Spring Data integrations provide contextual command read-model parameters with deterministic JPA/Mongo ownership. Tenant-routed JPA uses certified `JpaPersistenceUnit` values; tenant-routed MongoDB uses certified `TenantMongoOperations`. Their observable APIs require MongoDB change streams or an explicit JPA change notifier. Imperative JPA/Mongo command transactions are disabled by default and remain thread-bound, fixed-store opt-ins that must not cross coroutine threads. The Arc Chronicle starter transitively supplies Chronicle.Kotlin's Spring Boot starter and its conventional `IEventStore`; applications can override its beans normally. No integration provides a distributed transaction across Chronicle, JPA, and MongoDB. KSP cannot currently discover `@HandlesCommandResponseValues` annotations declared only in dependency binaries, and erased `CommandResponseValues` contents cannot provide typed response metadata; put custom handler declarations in the command's source compilation when proxy/OpenAPI classification is required. `@CreditCard` and Hibernate Validator `@CreditCardNumber` remain server-enforced and present in metadata, but are not emitted into TypeScript until the pinned `@cratis/arc` client runtime provides a compatible rule.

`LocalDateTime`, `Instant`, `OffsetDateTime`, and `ZonedDateTime` still map to JavaScript `Date`. The `LocalDateTime` mapping invents a zone, while offset/zoned values lose their original offset or zone identity. Raw `08:09:10.1235567` hydrates through the pinned `TimeOnly` as `08:09:10.123`, proving millisecond truncation rather than rounding, so it is not an exact precision round-trip. Arc accepts and emits `LocalTime` values with up to seven fractional digits for 100 ns compatibility. Deserialization rejects eight or nine fractional digits, and serialization rejects values finer than 100 ns rather than rounding or truncating them. This server binding is distinct from the shared `@cratis/arc` generated-client limitation: explicit RFC QUERY bodies still pass `DateOnly` or `TimeOnly` component objects to native `JSON.stringify`; use GET until upstream serialization uses the typed serializer or `toJSON()`. `Guid` is unaffected because it has `toJSON()`, and the JVM server continues to require scalar date/time strings.

Map support remains property-only: query parameters, top-level query/command response maps, non-string keys, nullable entries/elements, `ValueMap`, and model/concept/enum/UUID/temporal map leaves are rejected. Aggregate response metadata, calendar/UUID proxy fidelity, tenant-safe Spring Data command read models, and nested Chronicle transaction ownership are completed P0 slices. Chronicle now commits after local opt-in scopes, but thread-bound persistence and cross-store partial/indeterminate outcomes remain explicit limitations.

Start with the [documentation](Documentation/index.md), then consult module tests for executable contract details.
