# Arc Gradle plugin

The `io.cratis.arc` plugin configures Kotlin/JVM and KSP, targets JDK 17 with warnings treated as errors, adds the Arc runtime and KSP processor, and generates TypeScript proxies from Arc artifact manifests.

The plugin applies Kotlin/JVM and KSP `2.1.0-1.0.29` itself. Kotlin and Java sources in the main source set are processed. By default, both `io.cratis:arc` and `io.cratis:arc-ksp` use the plugin's version. Set `dependencyVersion` explicitly when consuming snapshots or substituting modules from a composite build. A dependency already supplied by the application is not added again.

Proxy generation is attached to `build` through `generateArcProxies`. It is skipped until `proxies.outputDirectory` is configured. The task reads every `META-INF/cratis/arc/*.json` manifest on the main output, compile classpath, and runtime classpath. Generated files are only rewritten when their content changes. Stale cleanup only removes files carrying the Arc generated marker; hand-written files are never deleted.

Proxy output uses one TypeScript file per command, query, model, or enum. This is the common .NET proxy-generator contract (`useSourceFileAsOutputFile = false`) and avoids output-name ambiguity by failing on duplicate artifact paths. The .NET generator also offers a separate, opt-in source-file grouping mode based on C# source metadata. Kotlin manifests do not carry equivalent source-file provenance, so that distinct grouping mode is not inferred or emulated here.

## Kotlin DSL

```kotlin
plugins {
    id("io.cratis.arc") version "<version>"
}

cratisArc {
    moduleName.set("Orders")

    // Optional override for snapshots or composite builds.
    dependencyVersion.set("<version>")

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

## Groovy DSL

```groovy
plugins {
    id 'io.cratis.arc' version '<version>'
}

cratisArc {
    moduleName.set('Orders')
    dependencyVersion.set('<version>')

    endpoints {
        routePrefix.set('api')
        segmentsToSkip.set(2)
        includeCommandNames.set(true)
        includeQueryNames.set(true)
        enableQueryHttpMethod.set(true)
    }

    proxies {
        enabled.set(true)
        outputDirectory.set(layout.projectDirectory.dir('ClientApp/src/api'))
        removeStaleGeneratedFiles.set(true)
        segmentsToSkip.set(2)
    }
}
```

The endpoint values must match the Spring Boot Arc endpoint configuration. Proxy routes are calculated with the same `ApiEndpointOptions` and `EndpointRouteHelper` used by the Spring integration. An explicit query `@Path` is preserved verbatim.

## Generator CLI

`io.cratis.arc.gradle.GenerateArcProxiesCli` is the production command-line entry point used by project-local generation tasks. It invokes the same `ArcManifestDiscovery` and `TypeScriptProxyGenerator` as `generateArcProxies`; it does not maintain a separate renderer. Supply one or more `--manifest-classpath <directory-or-jar>` values and `--output-directory <directory>`. Endpoint and output conventions can be set with `--route-prefix`, `--route-segments-to-skip`, `--include-command-names`, `--include-query-names`, `--enable-query-http-method`, `--remove-stale-generated-files`, and `--proxy-segments-to-skip`.

## Generated TypeScript type contract

The generator maps `java.time.LocalDate` to `DateOnly`, `java.time.LocalTime` to `TimeOnly`, and `java.util.UUID` to `Guid` from `@cratis/fundamentals`. The UUID mapping is an explicit decision for .NET proxy parity. Commands and generated GET query parameters serialize these values as scalar strings, while command responses and query data hydrate generated models and mapped fields into real class instances. OpenAPI remains string/date, string/time, and string/uuid.

`LocalDateTime`, `Instant`, `OffsetDateTime`, and `ZonedDateTime` remain JavaScript `Date`; this invents a zone for `LocalDateTime` and cannot retain original offset or zone identity. Arc disables Jackson's `WRITE_DURATIONS_AS_TIMESTAMPS` in Core and Spring, so `Duration` serializes and deserializes as ISO-8601 text, generated TypeScript remains `string`, and OpenAPI is `string`/`duration`. It is not mapped to Fundamentals `TimeSpan` because Java and C# use different wire formats. The currently tested `Period` behavior is Core ISO-8601 text round-trip plus TypeScript `string` generation. Raw `08:09:10.1235567` hydrates through the pinned `TimeOnly` as `08:09:10.123`, proving truncation rather than rounding, so it is not an exact precision round-trip.

Arc accepts and emits `LocalTime` values with up to seven fractional digits for 100 ns compatibility. Deserialization rejects eight or nine fractional digits, and serialization rejects values finer than 100 ns rather than rounding or truncating them. This is distinct from the shared `@cratis/arc` generated-client limitation for explicit RFC QUERY bodies: native `JSON.stringify` sees `DateOnly` or `TimeOnly` component objects because those classes lack `toJSON()`, rather than invoking their typed scalar serializer. Prefer GET until upstream serialization uses the typed serializer or `toJSON()`. `Guid` has `toJSON()` and is unaffected. The JVM endpoint still requires scalar date/time strings; the wrong component-object shape is not supported.

This generated TypeScript change is source-breaking: consumers must replace `Date`, time strings, and UUID strings assigned to affected generated properties with `DateOnly`, `TimeOnly`, and `Guid` class values. Generated interfaces use type-only imports where appropriate, and strict contracts enable `verbatimModuleSyntax`. Recursive type metadata moves the manifest to format 5 and adds immutable Java-friendly shape descriptors while preserving legacy constructor descriptors and compatibility getters.

## TypeScript compatibility gate

`:GradlePlugin:generateContractTestProxies` depends on `:ContractTests:kspTestFixturesKotlin`, reads the real `ContractTests` KSP manifest, and generates into `ContractTests/TypeScript/generated`. The directory is a clean, gitignored build fixture rather than a tracked golden fixture. Both route and proxy generation skip the five common `io.cratis.arc.contracts.fixtures` package segments, producing sensible root imports and routes while preserving explicit paths.

`:ContractTests:typeScriptBuild` runs an immutable `npm ci` from `package-lock.json`, generates the proxies twice, verifies that the second pass changes no bytes, and runs TypeScript in strict no-emit mode with `verbatimModuleSyntax` against the published `@cratis/arc`, `@cratis/arc.react`, and `@cratis/fundamentals` packages. Generated files remain untracked by design; stale cleanup only removes files bearing the generated marker.

`:ContractTests:typeScriptRuntimeTest` additionally builds the executable Kotlin Spring Boot sample, copies its real generated proxies, starts the application, and executes the published clients across command validation/execution, malformed envelopes, GET and RFC QUERY, paging/sorting, identity, direct and multiplexed WebSocket, direct SSE, and calendar/UUID transport and hydration. The harness has five wired unit tests plus 24 behavioral runtime tests: 14 general tests in a UTC Node process and five calendar tests in each of separate UTC and `America/Los_Angeles` Node processes. Counts are parsed from each TAP summary, which must contain the exact test/pass total and zero fail, cancelled, skipped, or todo results; Spring process-spawn errors fail cleanly. `:ContractTests:check` depends on this runtime E2E gate, so the root build reaches both compile-time and runtime contracts.

The plugin's differential unit test generates a shared JVM artifact fixture and compares its complete sorted path set and bodies exactly with a repository-local expected fixture intended for source control. Exact comparison occurs after CRLF-to-LF and volatile generated-header normalization, the expected fixture's capture-time namespace and query-name casing transformations, and expected-side .NET import rewrites required by `verbatimModuleSyntax`. The import rewrites make `SetCommandValues`, `ClearCommandValues`, and query helper types including `PerformQuery`, `SetSorting`, `SetPage`, `SetPageSize`, and `ChangeSet` type-only. One additional expected-side correction at `Commands/CreateFixtures.ts` changes the exact declaration `Command<ICreateFixtures, FixtureModel>` to `Command<ICreateFixtures, FixtureModel[]>`. Current .NET output already calls `super(FixtureModel, true)` and is enumerable at runtime, so the scalar generic is a known typing defect. The checked .NET-derived `FixtureModel.labelsByCategory` capture is already `@field(Object)` with `Record<string, string>` and receives no dictionary or `ValueMap` rewrite: after line-ending/header and import normalization that exact declaration is compared byte-for-byte. This proves only that one string-key/string-value Record fixture. It does not establish non-string keys, nullable entries, typed model values, `ValueMap`, or broader dictionary parity. The guarded normalizations never transform JVM output, so a JVM scalar regression still fails. The gate covers command, one-shot/observable query, model, interface, derived-type, enum, flags, validator, index, and that bounded Record shape, but the current .NET fixture contains no `Guid`, `DateOnly`, or `TimeOnly`; focused generator and contract tests cover that mapping. The differential proves only that JVM output has not drifted from this normalized fixture; it does not establish exact raw-output compatibility or broader .NET parity. Capture-time fixture preparation still needs reproducible tooling.

Request-response and observable query manifests use the same artifact-per-file output, deterministic metadata, import ordering, and index management. Existing hand-written index comments and exports are preserved when generated exports are added or removed.

The published `io.cratis:arc-gradle-plugin` implementation also has a checked binary-compatibility baseline. `apiCheck` fails when its public Kotlin/Java ABI changes without an intentional baseline update.
