---
title: Generate TypeScript proxies
description: Configure the Arc Gradle plugin to generate command, one-shot query, observable query, model, interface, and enum proxies.
---

## Configure generation

Use the `io.cratis.arc` plugin and set an output directory. Endpoint values must match the Spring host settings because the generator calculates the same routes.

```kotlin
plugins {
    id("io.cratis.arc") version "<version>"
}

cratisArc {
    moduleName.set("Orders")
    dependencyVersion.set("<version>")
    manageDependencies.set(true)

    endpoints {
        routePrefix.set("api")
        segmentsToSkip.set(2)
        includeCommandNames.set(true)
        includeQueryNames.set(true)
        enableQueryHttpMethod.set(true)
    }

    proxies {
        enabled.set(true)
        outputDirectory.set(layout.projectDirectory.dir("ClientApp/src/api"))
        removeStaleGeneratedFiles.set(true)
        segmentsToSkip.set(2)
    }
}
```

## Run the task

```bash
./gradlew generateArcProxies
```

The task is also attached to `build`. It reads `META-INF/cratis/arc/*.json` manifests from main output and classpaths, rewrites only changed content, and deletes only stale files bearing Arc's generated marker.

```mermaid
graph LR
    Models[Kotlin and Java models] --> KSP[KSP]
    KSP --> Manifest[Arc artifact manifest]
    Manifest --> Task[generateArcProxies]
    Task --> TS[Command, query, model, and enum TypeScript]
    TS --> Packages[@cratis/arc, @cratis/arc.react, @cratis/fundamentals]
```

## Generate observable clients

A manifest query with `OBSERVABLE` transport generates an `ObservableQueryFor` class. Enumerable queries include React hooks for observable snapshots, suspense, paging, sorting, and change streams; single-model queries include observable and suspense hooks. Generated `when` helpers conditionally subscribe, and client query parameters retain validation and HTTP preference metadata. Infrastructure-owned service, `QueryRequest`, and `QueryContext` parameters are omitted from one-shot and observable parameter interfaces, descriptors, required-argument lists, properties, validators, sorting helpers, React hooks, and imports.

The generated clients use the Spring host's direct or multiplexed observable transports provided by `@cratis/arc`. See [Expose one-shot and observable queries](queries.md) for the HTTP, SSE, and WebSocket contracts.

## Use JVM temporal and UUID types

Direct properties and the underlying values of Kotlin or Java `ConceptAs<T>` use the same generated TypeScript mapping. Concept wrappers remain strongly typed on the server but are erased to the mapped client type.

| JVM type or concept value | Generated TypeScript type | Runtime constructor |
| --- | --- | --- |
| `String` | `string` | `String` |
| `Int` / `Integer` | `number` | `Number` |
| `UUID` | `Guid` from `@cratis/fundamentals` | `Guid` |
| `LocalDate` | `DateOnly` from `@cratis/fundamentals` | `DateOnly` |
| `LocalTime` | `TimeOnly` from `@cratis/fundamentals` | `TimeOnly` |
| `LocalDateTime` | `Date` | `Date` |
| `Instant` | `Date` | `Date` |
| `OffsetDateTime` | `Date` | `Date` |
| `ZonedDateTime` | `Date` | `Date` |
| `OffsetTime` | `string` | `String` |
| `Duration` | `string` containing the ISO-8601 duration | `String` |
| `Period` | `string` containing the ISO-8601 period | `String` |
| Arc enum | generated enum | `Number` |

`UUID` intentionally maps to `Guid`, rather than `string`, for .NET proxy parity. Arc disables Jackson's `WRITE_DURATIONS_AS_TIMESTAMPS` in both its Core mapper and Spring configuration, so `Duration` serializes and deserializes as ISO-8601 text. Generated TypeScript therefore remains `string`, and OpenAPI describes it as `string`/`duration`. It is not mapped to Fundamentals `TimeSpan`: Java `Duration` and C# `TimeSpan` use different wire formats. The current tested `Period` contract is narrower: the Core mapper round-trips ISO-8601 text and the focused generator test maps it to TypeScript `string`. `OffsetTime` is deliberately textual and untyped in generated TypeScript: its offset remains in the wire string, but offset-specific semantics are not hydrated into a class.

The generated TypeScript API change is source-breaking. Existing consumers must replace JavaScript `Date` values for `LocalDate`, string values for `LocalTime`, and UUID string values with the mapped classes:

```typescript
import { DateOnly, Guid, TimeOnly } from "@cratis/fundamentals";
import { EchoCalendar } from "./generated/runtime/index.js";

const command = new EchoCalendar();
command.identifier = Guid.parse("11111111-1111-1111-1111-111111111111");
command.date = DateOnly.from(2026, 1, 1);
command.time = TimeOnly.from(14, 30, 45, 123);
```

Commands serialize `Guid`, `DateOnly`, and `TimeOnly` as scalar JSON strings. Generated GET query parameters serialize them as scalar URL values. Returned command responses and query data hydrate generated models and these fields into real class instances rather than leaving plain objects.

The pinned `TimeOnly` client retains milliseconds only. The runtime gate observes raw `08:09:10.1235567` from the JVM and hydration as `08:09:10.123`; because rounding would produce `.124`, this proves truncation rather than rounding. It is not an exact precision round-trip.

Arc accepts and emits `LocalTime` values with up to seven fractional digits for 100 ns compatibility. Deserialization rejects eight or nine fractional digits as malformed, and serialization rejects values finer than 100 ns rather than rounding or truncating them. That server binding is distinct from a remaining limitation in the shared `@cratis/arc` generated client. Its explicit QUERY path passes `DateOnly` and `TimeOnly` component objects to native `JSON.stringify`, because those classes do not provide `toJSON()`, instead of invoking their typed scalar serializer. Use generated GET queries for those parameters until upstream serialization uses the typed serializer or `toJSON()`. `Guid` is unaffected because it provides `toJSON()`. The JVM server does not accept the client library's wrong component-object shape.

`LocalDateTime`, `Instant`, `OffsetDateTime`, and `ZonedDateTime` remain JavaScript `Date`. A `LocalDateTime` has no zone, so constructing a JavaScript instant invents one. `OffsetDateTime` and `ZonedDateTime` can preserve the represented instant but not their original offset or zone identity. Use an application-owned representation when those distinctions are part of the domain contract.

Concept wrapper classes are not emitted or imported into the client. Imports are retained for generated underlying enums and model classes when Arc needs them for typing or runtime result construction. Type-only generated interfaces use `import type` where appropriate, and the strict contract enables `verbatimModuleSyntax` so accidental runtime imports fail compilation. The OpenAPI starter continues to describe `UUID`, `LocalDate`, and `LocalTime` as `string`/`uuid`, `string`/`date`, and `string`/`time`; the richer classes are generated-client types, not new wire shapes.

## Use string-keyed map properties

Command, model, interface, and read-model properties may use `Map<String, V>` / `java.util.Map<String, V>` when every entry is nonnullable and every leaf is string, boolean, character, byte, short, or integer. Generation is recursive: maps become `Record<string, V>`, sequence values append `[]`, and nested maps become nested `Record` types. Floating-point leaves are rejected because non-finite JVM and JavaScript values do not share one JSON representation. The map property's runtime constructor, `PropertyDescriptor`, and `@field` decorator use `Object`. A map property itself may be nullable; generated command accessors and model fields then include `undefined`. Jackson writes the map as a normal JSON object and OpenAPI emits `type: object` with recursive `additionalProperties` and the same property-name restrictions.

The keys `__proto__`, `prototype`, and `constructor` are reserved: Arc rejects them during Jackson map serialization/deserialization, and generated command setters reject reserved own keys and objects with unsafe prototypes before transport. Null-prototype records with ordinary keys are supported. The bounded contract deliberately does not enable `ValueMap`, non-string keys, nullable entries or sequence elements, floating-point leaves, typed model/concept/enum/UUID/temporal leaves, map query parameters, or top-level query/command response maps. No `_entries` or entry-array wire shape is emitted. Use a generated model containing map properties when a command response or query result needs map data.

```typescript
const command = new EchoMaps();
command.strings = { language: "typescript" };
command.numbers = { values: [1, 2] };
command.nested = { flags: { ready: true } };
```

## Preserve validation metadata

KSP translates exactly representable Jakarta constraints on command properties and query parameters into Arc validation rules. Arc's Jakarta-compatible `@Phone` and `@Url` annotations emit the TypeScript `phone` and `url` rules and preserve an explicitly supplied message. `@CreditCard` is enforced on the server and retained in manifest metadata, but is not emitted into TypeScript because `@cratis/arc` 22.7.0 has no `creditCard` rule-builder or runtime rule; generating the .NET extractor's current output would produce an uncompilable proxy. Their default server messages are `must be a valid phone number`, `must be a valid URL`, and `must be a valid credit card number`. Optional Hibernate Validator `@URL` maps to the client `url` rule when Hibernate Validator is on the application's compile classpath; `@CreditCardNumber` remains server-only for the same client-runtime limitation. Arc does not require Hibernate Validator at runtime.

The manifest carries those rules and recursive `@Valid` metadata, and the generator emits the matching TypeScript validators. Rules declared on a concept value are merged with rules declared on the owning command property or query parameter, so the browser enforces the same combined constraints as the server even though the wrapper is erased. Unsupported JavaScript regular expressions, custom groups/payloads, contradictory bounds, and other unrepresentable client constraints stop generation with `ARCKSP0301` rather than silently weakening client validation.

## Verify generated compatibility

Command, one-shot and observable query, model, interface, derived-type, enum, and flags proxies compile in strict mode against the actual `@cratis/arc`, `@cratis/arc.react`, and `@cratis/fundamentals` packages. The repository contract gate installs exactly from `package-lock.json`, generates twice to prove byte stability, and runs `tsc --noEmit` with `verbatimModuleSyntax` enabled.

The `:ContractTests:typeScriptRuntimeTest` gate then builds the executable Kotlin Spring Boot sample, copies its real generated proxies, starts the application, and executes the published TypeScript clients against it. The harness has five wired unit tests and enforces 25 behavioral runtime tests: 15 general tests in one UTC Node process, plus five calendar tests in a separate UTC process and the same five in a separate `America/Los_Angeles` process. It parses each TAP summary and requires the exact test/pass count with zero fail, cancelled, skipped, or todo results; a Spring process-spawn error fails cleanly. The behavioral contract covers command validation and typed responses, malformed envelopes, correlation IDs, GET and RFC QUERY, paging and sorting, identity, multiplexed and direct WebSocket observable queries, direct SSE, scalar calendar/UUID serialization, generated-model hydration, timezone stability, and the documented `TimeOnly` truncation. This is an E2E runtime gate rather than a renderer-only fixture.

Recursive type shapes use artifact manifest format 5 and immutable Java-friendly `TypeShapeDescriptor` metadata while preserving legacy JVM constructor descriptors and compatibility getters. Generated format 5 query parameter nodes include an explicit canonical value `source`; canonical file discovery requires it while legacy programmatic `ParameterDescriptor` constructors still project to `CLIENT` or `SERVICE` and serialize canonically. Unversioned, legacy-only, contradictory, or unsupported-context manifests fail. The temporal/UUID generated TypeScript type changes remain source-breaking for client consumers as described above.

A separate differential test generates a shared JVM artifact fixture and compares its complete sorted output path set and bodies exactly with a repository-local expected fixture intended for source control. Exact comparison occurs after CRLF-to-LF and volatile generated-header normalization, the expected fixture's capture-time namespace and query-name casing transformations, and expected-side .NET import rewrites required by `verbatimModuleSyntax`. Those rewrites mark `SetCommandValues`, `ClearCommandValues`, and query helper types including `PerformQuery`, `SetSorting`, `SetPage`, `SetPageSize`, and `ChangeSet` as type-only imports. One expected-side correction in that repository-local fixture at `Commands/CreateFixtures.ts` also changes the exact declaration `Command<ICreateFixtures, FixtureModel>` to `Command<ICreateFixtures, FixtureModel[]>`. Current .NET output already calls `super(FixtureModel, true)` and is enumerable at runtime, so the scalar generic is a known typing defect. The .NET-derived `FixtureModel.labelsByCategory` capture already contains `@field(Object)` and `Record<string, string>` and receives no dictionary rewrite; after the documented line-ending/header and import normalization, that exact declaration is compared byte-for-byte. It proves only this string-key/string-value Record fixture, not non-string keys, nullable entries, typed model values, `ValueMap`, or broader dictionary parity. The guarded normalizations leave actual JVM output untouched, so a JVM scalar regression still fails. Covered shapes include commands, one-shot and observable queries, models, interfaces, derived types, enums, flags, validators, indexes, and that bounded Record fixture. The current .NET fixture contains no `Guid`, `DateOnly`, or `TimeOnly`, so the temporal/UUID mapping itself is covered by focused generator and contract tests rather than this differential. The gate detects drift from the normalized fixture; it does not establish exact raw-output compatibility or broader Arc .NET parity. Capture-time fixture preparation still needs reproducible tooling.

Published Kotlin APIs are also guarded by checked-in binary-compatibility baselines. `apiCheck` covers the runtime integrations and testing module as well as `arc-ksp` and `arc-gradle-plugin`, so compiler and build-tool contracts cannot drift without an intentional baseline update.
