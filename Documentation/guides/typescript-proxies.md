---
title: Generate TypeScript proxies
description: Configure the Arc Gradle plugin to generate command, one-shot query, observable query, model, interface, and enum proxies.
---

## Share model validation

[Fluent validation](validation.md) contributes verified declarations to the existing renderer. Active models export `<Model>Validator`; command and supported RFC QUERY validators compose nested shared rules, including indexed siblings. Structurally equal duplicate model descriptors merge validation conjunctions; incompatible structures reject. Annotation-only output and its differential boundary are unchanged. Manual shared-generation CLI calls require `--module-name` and the complete compiled root/dependency classpath, not only manifest resources. Follow the [generated excerpt and handwritten caller](validation.md#display-generated-client-feedback), or consult the [dependency extraction and root inventory contract](../reference/validation.md#manual-build-tooling).

## Configure generation

Use the `io.cratis.arc` plugin and set an output directory. Route configuration exists on both sides — build-time (`cratisArc.endpoints.*`) and runtime (`cratis.arc.endpoints.*`) — and both sides must agree. The Arc Spring Boot starter enforces this automatically: when proxies were generated, it reads the build-time settings from a small resource file written by the plugin at proxy-generation time and fails startup with an error that names the disagreeing setting and both values if they differ. This check is active only when proxies were generated; applications that do not generate proxies start without it.

> **Note on similar-looking settings:** `cratisArc.proxies.segmentsToSkip` and `cratisArc.endpoints.segmentsToSkip` are two different settings. `proxies.segmentsToSkip` controls how many leading package segments are stripped when deciding the _file path_ of a generated TypeScript file. `endpoints.segmentsToSkip` controls how many leading package segments are stripped when computing the _HTTP route_ the generated client calls. The runtime counterpart is `cratis.arc.endpoints.segments-to-skip-for-route`. Mixing them up produces the wrong file layout or the wrong route, not a startup error.

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

## Share types with an existing npm package

A type can be answered by configuration instead of being generated, so a model already published in a
TypeScript package is imported rather than duplicated.

```kotlin
proxies {
    // A JVM type that should cross the wire as a plain TypeScript type.
    mapType("java.time.Duration", "string")

    // A JVM type that is imported from an npm package.
    mapType("io.acme.shared.Money", "Money", "@acme/models")

    // Every model type under a JVM package, imported by its simple name.
    mapPackage("io.acme.shared.model", "@acme/models")
}
```

Type mappings are consulted **ahead of** the generator's built-in type map, so an entry can correct an
existing mapping as well as declare one the generator has never seen. Concepts are unwrapped first, so
map the concept's underlying type rather than the concept.

A mapped type is **not generated**. Emitting it as well would put a local declaration and an external
import of the same name in scope. For a package mapping the longest matching JVM package wins, so a
nested package can override a broader one.

Unpackaged overrides support `string`, `number`, `boolean`, `object`, and `Date`, with their actual
JavaScript runtime constructors (`String`, `Number`, `Boolean`, `Object`, and `Date`). Packaged
mappings require a named export. Manifest interfaces use type-only imports and `Object` descriptors;
manifest numeric enums use type-only imports and `Number` descriptors. Other mapped types must export
a constructible class with fields registered using Fundamentals `@field` decorators. A plain,
undecorated class is not a hydration contract: its payload fields may be lost. Mappings change client
generation, not server serialization; the package must honor the existing JSON wire shape.

A missing or blank mapping component is named in a build warning and skipped. Nonblank but unsafe or
unrepresentable mappings, import collisions, and overrides of command or concept declarations fail
before output is written. Map the properties/response of a command or the underlying value of a concept
instead. Arbitrary TypeScript expressions, unions, arrays in a type name, and ambient undeclared
constructors are not supported. Map values only support primitive overrides; external map-value
hydration and externally mapped polymorphic bases/derivatives are not supported.

The standalone CLI takes the same values as repeatable
`--type-to-typescript <FullyQualifiedTypeName>=<TypeScriptType>[=<NpmPackage>]` and
`--package-to-npm <JvmPackage>=<NpmPackage>` options. The value is split on at most two separators.
Anything after the second stays in the package field and is validated as part of its import path; it is never silently discarded. Neither the exported
identifier nor the npm import path may contain an `=`.

## Run the task

```bash
./gradlew generateArcProxies
```

The task is also attached to `build`. It reads `META-INF/cratis/arc/*.json` manifests from main output and classpaths, rewrites only changed content, and deletes only stale files bearing Arc's generated marker.

The `io.cratis.arc` plugin also registers a `writeArcEndpointOptionsResource` task that writes `META-INF/arc/endpoint-options.json` to the main source set resources. The Spring Boot starter reads this file at startup and fails fast when any setting disagrees with the runtime configuration. Both routes — the Gradle task and the standalone CLI — delegate to the same serialiser, so their output is byte-identical for the same options.

```mermaid
graph LR
    Models[Kotlin and Java models] --> KSP[KSP]
    KSP --> Manifest[Arc artifact manifest]
    Manifest --> Task[generateArcProxies]
    Task --> TS[Command, query, model, and enum TypeScript]
    TS --> Packages[@cratis/arc, @cratis/arc.react, @cratis/fundamentals]
    Task --> Options[META-INF/arc/endpoint-options.json]
    Options --> Check[Spring Boot startup consistency check]
```

## Use the standalone CLI

Projects that invoke the proxy generator directly through `io.cratis.arc.gradle.GenerateArcProxiesCli` instead of applying the `io.cratis.arc` Gradle plugin must pass `--endpoint-options-output <dir>` to activate the same Spring Boot startup consistency check. Without the flag the CLI behaves exactly as before and no resource is written.

```kotlin
// build.gradle.kts — JavaExec task that calls the CLI directly
val generateArcProxies by tasks.registering(JavaExec::class) {
    classpath = arcProxyGenerator
    mainClass.set("io.cratis.arc.gradle.GenerateArcProxiesCli")
    args(
        "--manifest-classpath", ...,
        "--output-directory", ...,
        "--route-prefix", "api",
        "--route-segments-to-skip", "5",
        "--proxy-segments-to-skip", "5",
        // Activates the startup consistency check for projects that don't use the Gradle plugin.
        "--endpoint-options-output", layout.buildDirectory.dir("generated/arc-endpoint-options/main").get().asFile.absolutePath
    )
}
```

The output directory must be on the application runtime classpath. When the project uses `processResources` to copy resources, add it as a resource source directory and make `processResources` depend on the generation task:

```kotlin
sourceSets.main.get().resources.srcDir(layout.buildDirectory.dir("generated/arc-endpoint-options/main"))
tasks.named("processResources") { dependsOn(generateArcProxies) }
```

This is safe only when the CLI task does not itself depend on `processResources`. If it does (because it depends on `classes`, which includes `processResources`), target `build/resources/main` directly and make the JAR assembly task depend on the generation task instead.

All five endpoint-option flags mirror the Gradle extension settings:

| CLI flag | Extension setting | Default |
| --- | --- | --- |
| `--route-prefix` | `endpoints.routePrefix` | `api` |
| `--route-segments-to-skip` | `endpoints.segmentsToSkip` | `0` |
| `--include-command-names` | `endpoints.includeCommandNames` | `true` |
| `--include-query-names` | `endpoints.includeQueryNames` | `true` |
| `--enable-query-http-method` | `endpoints.enableQueryHttpMethod` | `true` |

## Generate observable clients

A manifest query with `OBSERVABLE` transport generates an `ObservableQueryFor` class. Enumerable queries include React hooks for observable snapshots, suspense, paging, sorting, and change streams; single-model queries include observable and suspense hooks. Generated `when` helpers conditionally subscribe, and client query parameters retain validation and HTTP preference metadata. Infrastructure-owned service, `QueryRequest`, and `QueryContext` parameters are omitted from one-shot and observable parameter interfaces, descriptors, required-argument lists, properties, validators, sorting helpers, React hooks, and imports.

The generated clients use the Spring host's direct or multiplexed observable transports provided by `@cratis/arc`. See [Expose one-shot and observable queries](queries.md) for the HTTP, SSE, and WebSocket contracts.

## Sort by returned-row fields

For enumerable queries whose metadata advertises sorting, `sortBy` helpers name properties of the
**returned model**, including class-base properties already represented in the manifest. Request-only
arguments such as `filter` do not become sort keys. Parameterless queries can therefore expose useful
row-field helpers too. Both one-shot and observable clients follow this rule.

This does not grant new paging or sorting capabilities, infer database indexes, or guarantee that every
field is sortable by a custom provider. The renderer/provider still determines which keys it supports;
use an explicit `Sorting` value for provider-specific keys not present in result metadata. Scalar and
empty result models have no named field helpers. Interface inheritance not represented in the manifest
is not invented by the generator.

Regenerate clients when upgrading: helpers for request-only arguments disappear, and the helper's old
public `query` owner reference is no longer emitted, allowing a result field named `query` to have its
own sorting helper. Private helper storage is collision-safe for fields such as `_name` and `name`,
including inherited fields. A member named `constructor` cannot be emitted as a class accessor and
fails generation with an actionable error; expose a different result contract for that case.
Arc .NET at `v22.14.0` also derives model-bound helper names from method parameters;
using result fields here is a deliberate JVM correctness divergence, not matching raw .NET output.

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

## Discover identity details

KSP automatically exports the details graph of public concrete source providers implementing
`IdentityDetailsProvider<T>` or `AsyncIdentityDetailsProvider<T>`. It also reads public typed factory
returns: Kotlin top-level, member and companion functions, and ordinary Java static or instance
methods. Spring `@Bean` is not required. The Kotlin sample's anonymous provider is discoverable from
its factory's `IdentityDetailsProvider<SampleIdentityDetails>` return type; KSP does not inspect the
object body, evaluate `detailsType`, instantiate providers, or register runtime beans.

Inherited generic bindings are resolved through provider base classes. Abstract generic provider
bases, including implicitly abstract Kotlin sealed classes, remain templates; a concrete provider or
factory must establish a supported, public top-level
concrete details class with no type parameters. Raw, wildcard/star-projected, `Any`/`Object`, generic,
inaccessible and unsupported details shapes fail with `ARCKSP0307` (graph failures also retain their
specific model diagnostic). Bind a concrete details type and correct its graph rather than relying
on runtime erasure. Existing supported nested property graphs are traversed; this does not broaden
DTO shape support.

Identity-only compilations emit the same artifact module, service entry and format-8 manifest with
empty command/query lists. Roots and graph metadata are rebuilt from current-round source declarations,
including generated declarations alongside already valid roots; native Kotlin and Java incremental
checks cover correction, addition, removal, inherited generic binding changes and reachable DTO property
edits. KSP artifact comparisons use fresh output and project-cache directories and require KSP to execute;
proxy assertions separately check the changed roots and fields. `ArcSymbolProcessorIdentityCompilationTest`, `ArcIdentityDiscoveryFunctionalTest`,
`GeneratedIdentityArtifactsTest`, `IdentityArtifactsJavaContractTest` and
`GeneratedTypeScriptProxiesTest` cover this declaration-to-client path.

Dependency-only providers are not scanned wholesale. Compile tests demonstrate source bindings through
compiled Kotlin and ordinary Java generic provider bases, and a dependency-resident Kotlin data-class
DTO with a string property. This is not evidence for arbitrary binary DTOs or Java binary record
property discovery. Use the existing
`@ExportedType` on a supported source DTO when no public source provider/factory declaration exposes
it. Explicit export remains useful for unrelated DTOs, but is unnecessary for the sample's typed
identity factory. This is compile-time discovery, not .NET assembly scanning or bulk library export;
no runtime provider registration or raw-output equivalence is implied.

## Include declared body properties

Generated command and model metadata preserves public constructor-property order and appends remaining eligible public declared Kotlin properties sorted by name. Backed body `var`, backed `val`, and private-setter `var` state uses the existing shape, key, constraint, summary, and reachable-model machinery. Member `@JsonIgnore` and nonpublic Kotlin state are excluded. A model's inherited state remains in its separate base model, with declared overrides retained; inherited command state is not established by this change.

Computed getters remain available on output-only models, but computed or explicitly read-only Kotlin state in command input graphs fails with `ARCKSP0300`, also when the root is a Java record. Unrepresentable body Jackson renames, write-only access, or split ignore/property annotations likewise fail rather than emitting falsely writable or wrongly named fields. Use default Arc wire names, backed input properties, and separate output models; see [command body state](commands.md#declare-body-state-explicitly). Arbitrary application Jackson overrides are not inferred by KSP.

This corrects previously missing generated fields, body command keys, and validation; review the resulting client changes. Body initializers are not copied as TypeScript defaults. That body-state correction did not itself change the manifest schema; current [format 8](../reference/validation.md#manifest-format-8-migration) additionally carries explicit validation-ignore edges. Native contract fixtures assert real Jackson wire names, generated Kotlin and ordinary-Java invocation, manifest/discovery metadata, and TypeScript text; incremental add/remove and invalid-to-valid builds assert byte-stable regenerated artifacts. These checks do not establish broader Arc .NET parity.

## Declare nonnullable sequence elements

Command, model, interface, and read-model properties may use nullable outer `List<T>?`, `Collection<T>?`, and `Array<T>?` containers with nonnullable elements. The manifest retains outer nullability and the sequence kind; generated model fields remain optional `T[]` fields. Nullable entries such as `List<T?>`, `Collection<T?>?`, and `Array<T?>?` are unsupported and now fail at the member with `ARCKSP0300`, before an incompatible manifest can reach proxy discovery.

Java `List<@Nullable T>` and `List<@CheckForNull T>` entries are explicitly nullable, including record components handled through source fallback. Ordinary unannotated Java platform elements remain accepted; platform types are not explicit nullable declarations. This tightens source acceptance: previously accepted nullable-entry declarations must change to nonnullable elements. It does not independently change the manifest schema, expand collection-interface query support, or relax existing Java array and generic-variance restrictions.

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

## Propagate source documentation

Kotlin KDoc and Java Javadoc reach the generated clients as single-line JSDoc comments. Documentation is read at compile time, carried in the artifact manifest, and rendered by the generator; nothing is read at runtime.

```kotlin
/**
 * Creates an order for a customer.
 *
 * @property customerId Customer placing the order.
 */
@Command
public data class CreateOrder(
    /** Stable order identifier. */
    @CommandKey public val orderId: String,
    public val customerId: String
)
```

```typescript
/** Creates an order for a customer. */
export interface ICreateOrder {
    /** Stable order identifier. */
    orderId?: string;
    /** Customer placing the order. */
    customerId?: string;
}
```

Summaries are captured for commands, query methods, client query parameters, model types and their serializable properties, interfaces and their properties, and enum declarations. A member without its own comment falls back to a Kotlin `@property` tag on the declaring class or a Java `@param` tag on the declaring class or record; a Kotlin `@param` tag documents a constructor parameter and is deliberately not projected onto a property.

Only the first paragraph becomes a summary, folded onto one line and limited to 512 Unicode code points. A leading fenced, indented, or tab code block is skipped, control characters are dropped, and CR/LF plus the Unicode line controls `U+0085`, `U+2028`, and `U+2029` all end a line. The generator neutralizes a comment terminator and an at sign in tag position so a comment cannot end early or be misread as a JSDoc tag.

Documentation is emitted on the command interface and class, the command interface property and its public getter, the query parameter interface and query class, client parameter fields inside the parameter interface, the model class and its generated fields before the decorators, the interface and its fields, and the enum declaration. It is deliberately absent from setters, private backing fields, runtime query fields, enum members, and barrel files, and OpenAPI keeps its conventional descriptions.

Service, request, context, and host-adapter query parameters never carry documentation, because they never reach a generated client.

## Verify generated compatibility

Command, one-shot and observable query, model, interface, derived-type, enum, and flags proxies compile in strict mode against the actual `@cratis/arc`, `@cratis/arc.react`, and `@cratis/fundamentals` packages. The repository contract gate installs exactly from `package-lock.json`, generates twice to prove byte stability, and runs `tsc --noEmit` with `verbatimModuleSyntax` enabled.

The `:ContractTests:typeScriptRuntimeTest` gate then builds the executable Kotlin Spring Boot sample, copies its real generated proxies, starts the application, and executes the published TypeScript clients against it. The harness has five wired unit tests and enforces 33 behavioral runtime tests: four ignore-validation, four shared-fluent, and 15 general tests in separate UTC Node processes, plus five calendar tests in a separate UTC process and the same five in a separate `America/Los_Angeles` process. It parses each TAP summary and requires the exact test/pass count with zero fail, cancelled, skipped, or todo results; a Spring process-spawn error fails cleanly. The behavioral contract covers command validation and typed responses, malformed envelopes, correlation IDs, GET and RFC QUERY, paging and sorting, identity, multiplexed and direct WebSocket observable queries, direct SSE, scalar calendar/UUID serialization, generated-model hydration, timezone stability, and the documented `TimeOnly` truncation. This is an E2E runtime gate rather than a renderer-only fixture.

Recursive type shapes and source summaries were introduced in artifact manifest format 6. Format 7 added optional typed command `eventMetadata`; current format 8 adds mandatory property `ignoreValidation` booleans while preserving immutable Java-friendly `TypeShapeDescriptor` metadata, legacy JVM constructor descriptors, and compatibility getters. Rebuild producer/dependency manifests and consumers in lockstep; see the [format-8 migration](../reference/validation.md#manifest-format-8-migration). A command without event defaults omits `eventMetadata`; the reader accepts exactly the current format so a classpath cannot silently mix metadata generations. Generated format 8 query parameter nodes include an explicit canonical value `source`; canonical file discovery requires it while legacy programmatic `ParameterDescriptor` constructors still project to `CLIENT` or `SERVICE` and serialize canonically. Unversioned, legacy-only, contradictory, or unsupported-context manifests fail. The temporal/UUID generated TypeScript type changes remain source-breaking for client consumers as described above.

The separate `ArcGradlePluginTest` test `raw jvm proxy bytes equal the prepared expected fixture` generates a shared JVM artifact fixture and compares all regular files against the repository-local .NET-derived fixture under `GradlePlugin/src/test/resources/differential/dotnet/`. It compares sorted relative paths (only path separators are normalized), then untouched JVM bytes with `assertArrayEquals`, including headers, whitespace, line endings, and terminal newlines. Expected preparation never reads actual output or calls the production hash helper. Failures report byte lengths and the first differing byte offset. Mutation tests use this same reader, expected preparer, and comparator, rejecting altered bytes and paths, including a changed body with an internally valid recomputed hash.

This remains a drift gate for the normalized fixture, not an exact raw .NET-output comparison; capture-time fixture preparation is not yet reproducible tooling. Capture SDK and tool versions remain unverified. The .NET-derived `FixtureModel.labelsByCategory` capture already contains `@field(Object)` and `Record<string, string>` and receives no dictionary-shape rewrite, although its file receives the formatting preparation below. It proves only this string-key/string-value Record fixture, not non-string keys, nullable entries, typed model values, `ValueMap`, or broader dictionary parity. Covered shapes include commands, one-shot and observable queries, models, derived types, enums, flags, validators, indexes, and that bounded Record fixture. `Contracts/Shape.ts` is a class, not proof of interface emission. The current .NET fixture contains no `Guid`, `DateOnly`, or `TimeOnly`, so the temporal/UUID mapping itself is covered by focused generator and contract tests rather than this differential. Overall Arc .NET parity remains Partial.

### Verify the separate captured baseline offline

The seven-file `differential/captured` comparison is separate from the nineteen-file historical fixture
above. `:GradlePlugin:verifyCapturedProxyBaseline` runs before `:GradlePlugin:test` and binds that
seven-file snapshot to the reviewed capture-input hashes and SDK/runtime/package/tool pins. Mutation
tests prove that stale scripts/lockfiles, changed proxy bytes, renewed snapshot checksums, and malformed
or incomplete metadata fail. Normal verification reads local repository files only; it requires Python
but no .NET SDK, network, package cache, or retained session capture.

The baseline receipt is a consistency check, not cryptographic proof that a publisher's tool ran.
After changing a generation input, use the capture harness's explicit pinned-package capture and
`verify_baseline.py --candidate <full-capture>` workflow, review the normalized diff, then update the
receipt and fixture together where necessary. The candidate command verifies raw-to-normalized bytes
and current input pins, and prints JSON without changing either reviewed fixture. See the harness
README under `GradlePlugin/src/test/resources/differential/capture/` for the exact commands and safety
boundaries. Do not update hashes alone to clear stale-input failures. Overall parity remains Partial.

### Expected-only differential preparation

Historical capture-time namespace and query-name casing transformations and removal of timestamps/hashes are already embedded in the 19 checked-in files; this test does not reproduce that capture. Its executable preparation is limited to:

1. Decode expected UTF-8, convert CRLF to LF, remove trailing whitespace on each expected line, and finish with one terminal LF.
2. In `Models/FixtureModel.ts`, replace double quotes with single quotes and three-space indentation with four spaces; preserve the Record declaration.
3. In `Commands/CreateFixtures.ts`, replace double quotes with single quotes, flatten the multiline React command import, change `this.ruleFor((c) =>` to `this.ruleFor(c =>`, expand the empty request-parameter array layout, and flatten the class declaration and static hook declaration/call.
4. At exactly one known `CreateFixtures` class declaration, correct `Command<ICreateFixtures, FixtureModel>` to `Command<ICreateFixtures, FixtureModel[]>`. The captured command already calls `super(FixtureModel, true)`; its scalar generic contradicts enumerable runtime behavior.
5. At exactly the known `CreateFixtures` static hook return, insert the `eslint-disable-next-line @typescript-eslint/ban-ts-comment` and `@ts-ignore` pair. Only the complete pair immediately before the exact return is accepted if already present; missing/duplicate hooks or misplaced/incomplete/additional suppressions fail preparation. This is not a type-soundness claim for ignored hook calls.
6. Apply five literal type-only import rewrites for `verbatimModuleSyntax`: React command `SetCommandValues`/`ClearCommandValues`; pageable one-shot `PerformQuery`/`SetSorting`/`SetPage`/`SetPageSize`; non-pageable one-shot `PerformQuery`/`SetSorting`; observable `ChangeSet`; and observable React `SetSorting`/`SetPage`/`SetPageSize`. Some are no-ops on the current capture; the one-shot rewrites remain active.
7. Only in `Models/Observe.ts`, require exactly one literal `ObserveParameters` interface block with a zero-space blank line immediately before its four-space-indented `filter: string;` member. Replace that blank line with exactly four spaces. This is a parameter-bearing interface, not an empty interface; `ObserveOne.ts` has no parameter interface and is not included. Missing/duplicate anchors fail preparation, other paths/blocks stay untouched, and wrong actual whitespace still fails the comparison.
8. For exactly `Models/All.ts`, `Models/Search.ts`, and `Models/Observe.ts`, replace the two parameter-derived sort-helper classes with return-field helpers for the literal list `identifier`, `labelsByCategory`, `permissions`, `state`, `updatedAt`. Their constructor no longer stores a public `query` field. `ExpectedSortHelperPreparation` checks unique class/end anchors and a fixed SHA-256 of each original block before changing it; the literal field list is cross-checked against fixture model metadata, never actual output. Only `All.ts` gains the previously absent `SortingActions`/`SortingActionsForQuery` imports. Request parameters, routes, hooks, and capability flags are untouched. Missing/duplicate/changed source blocks fail, and an actual helper pointing to `filter` instead of `identifier` fails the byte comparison. This is an explicit semantic correction for the JVM result-field behavior, not source-output parity.
9. Validate each captured source-only first line against the independent source table below, cross-checked against the JVM fixture descriptors. Remove only that validated expected header, SHA-256 hash the prepared expected body as UTF-8 (including the DO NOT EDIT banner and terminal LF), and prepend `// @generated by Cratis. Source: <source>. Hash: <64 uppercase hex>` plus LF. The independent hash test asserts the fixed `abc` vector `BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD`. Unknown paths, wrong sources, and missing/duplicate markers fail preparation.

No normalization transforms JVM output. The three indexes `Commands/index.ts`, `Contracts/index.ts`, and `Models/index.ts` stay headerless. The 16 artifact identities are literal expected data, not values inferred from actual headers. Queries use their declaring model, not their query fully qualified name.

| Fixture path | Expected source identity |
| --- | --- |
| `Commands/CreateFixtures.ts` | `differential.fixture.commands.CreateFixtures` |
| `Contracts/Shape.ts` | `differential.fixture.contracts.Shape` |
| `Models/All.ts` | `differential.fixture.models.FixtureModel` |
| `Models/ById.ts` | `differential.fixture.models.FixtureModel` |
| `Models/Circle.ts` | `differential.fixture.models.Circle` |
| `Models/EmptyModel.ts` | `differential.fixture.models.EmptyModel` |
| `Models/FixtureModel.ts` | `differential.fixture.models.FixtureModel` |
| `Models/FixturePermissions.ts` | `differential.fixture.models.FixturePermissions` |
| `Models/FixtureState.ts` | `differential.fixture.models.FixtureState` |
| `Models/GetEmpty.ts` | `differential.fixture.models.EmptyModel` |
| `Models/GetShapes.ts` | `differential.fixture.models.ShapeHolder` |
| `Models/Observe.ts` | `differential.fixture.models.FixtureModel` |
| `Models/ObserveOne.ts` | `differential.fixture.models.FixtureModel` |
| `Models/Search.ts` | `differential.fixture.models.FixtureModel` |
| `Models/ShapeBase.ts` | `differential.fixture.models.ShapeBase` |
| `Models/ShapeHolder.ts` | `differential.fixture.models.ShapeHolder` |

Published Kotlin APIs are also guarded by checked-in binary-compatibility baselines. `apiCheck` covers the runtime integrations and testing module as well as `arc-ksp` and `arc-gradle-plugin`, so compiler and build-tool contracts cannot drift without an intentional baseline update.
