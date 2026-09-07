---
name: ksp-code-generation
description: Use when changing the Arc KSP symbol processor or anything it emits — generated handlers and performers, the artifact manifest, descriptor metadata, or an ARCKSP compile-time diagnostic — including when a downstream consumer of the manifest must change with it.
---

# Change the KSP processor or what it generates

`:CodeGeneration:KSP` turns annotated Kotlin and Java sources into reflection-free command
handlers, query performers, an artifact module, and one JSON manifest per compilation. The
Gradle plugin consumes that manifest to render TypeScript proxies. A processor change is
therefore never local: it moves generated code, a versioned transport contract, and possibly
a compile-time diagnostic at once.

Invariants live in [`../../rules/ksp.md`](../../rules/ksp.md); the build and baseline rules in
[`../../rules/gradle.md`](../../rules/gradle.md). This file is the procedure.

## 1. Read the current processor before changing it

The whole processor lives under
`CodeGeneration/KSP/src/main/kotlin/io/cratis/arc/codegeneration/ksp/`:

| File | Responsibility |
| --- | --- |
| `ArcSymbolProcessorProvider.kt` | The only published type; it is the entire `.api` baseline |
| `ArcSymbolProcessor.kt` | Discovery, validation, rendering, and `generateModule` |
| `MetadataCollector.kt` | Types, interfaces, enums, and concepts gathered for the manifest |
| `ValidationMetadataExtractor.kt` | Jakarta constraint translation |
| `EnumValueParser.kt`, `JavaRecordParser.kt`, `Naming.kt` | Focused helpers |
| `ArcDiagnostics.kt` | The stable `ARCKSP` catalog and the reporter |

Constants that define the contract are in the `private companion object` at the end of
`ArcSymbolProcessor.kt` — the option name `arc.moduleName`, the annotation FQNs
(`io.cratis.arc.artifacts.Command`, `io.cratis.arc.artifacts.ReadModel`,
`io.cratis.arc.artifacts.FromServices`, …), the handler name `handle`, the provide name
`provide`, the infrastructure parameter types, and the reserved query parameter names
`page`, `pagesize`, `sortby`, `sortdirection`. Read them rather than assuming.

`finish()` emits nothing unless `arc.moduleName` is configured and at least one command or
query was found. When it does emit, it writes three things:

- the generated artifact module class in the generated package;
- `META-INF/services/io.cratis.arc.artifacts.ArcArtifactModule`;
- `META-INF/cratis/arc/<moduleName>.json`, the manifest.

## 2. Treat the manifest as a versioned transport contract

The manifest schema is `ArcArtifactManifest` in
`Source/src/main/kotlin/io/cratis/arc/artifacts/ArcArtifactManifest.kt`. The code declares:

```kotlin
public const val CURRENT_FORMAT_VERSION: Int = <n>
```

Read the current value from the source; it moves whenever the manifest contract does.

The reader is `GradlePlugin/src/main/kotlin/io/cratis/arc/gradle/ArcManifestDiscovery.kt`. Its
`validateCanonicalManifest` is deliberately unforgiving: a missing or non-integral
`formatVersion` fails, any value other than `CURRENT_FORMAT_VERSION` fails, named legacy
fields (`responseTypeName`, `responseIsEnumerable`, `typeName`, `isNullable`, `isEnumerable`,
`elementTypeName`, `isFromServices`) fail if present, a missing canonical `shape` /
`returnShape` / `source` node fails, and every query parameter must carry a boolean
`hasDefault`.

So a manifest field change is a lockstep change across four places in one commit:

1. the descriptor in `Source/src/main/kotlin/io/cratis/arc/metadata/`;
2. the writer in `ArcSymbolProcessor.buildManifest` / `MetadataCollector`;
3. the reader and its validation in `ArcManifestDiscovery`;
4. `CURRENT_FORMAT_VERSION`, if the change is not purely additive-and-optional.

Bumping the version invalidates every previously published manifest, including manifests
inside dependency jars, because the reader rejects mismatches outright. Prefer an additive,
validated field over a bump, and say explicitly in the PR which you chose and why.

## 3. Add the rule with positive and negative fixtures together

Never land a processor rule without both directions of evidence.

**Positive** — a compile-testing test in
`CodeGeneration/KSP/src/test/kotlin/io/cratis/arc/codegeneration/ksp/`. Follow the existing
harness shape used by `ArcSymbolProcessorCommandResponseCompilationTest.kt`,
`ArcSymbolProcessorQueryDefaultsCompilationTest.kt`, and
`ArcSymbolProcessorSpringDataCompilationTest.kt`:

```kotlin
private fun compile(sources: List<SourceFile>): JvmCompilationResult = KotlinCompilation().apply {
    useKsp2()
    this.sources = sources
    inheritClassPath = true
    symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())
    kspProcessorOptions = mutableMapOf("arc.moduleName" to "NegativeContracts")
    kspWithCompilation = true
    messageOutputStream = System.out
}.compile()
```

`kspWithCompilation = true` matters: it proves the generated Kotlin actually compiles, not
only that it was produced. Assert on the generated text and on `ExitCode.OK`.

**Negative** — add the invalid Kotlin or Java source under
`ContractTests/src/negativeFixtures/kotlin/io/cratis/arc/contracts/negative/` or
`.../java/io/cratis/arc/contracts/negative/`. `ArcSymbolProcessorNegativeCompilationTest`
walks that whole tree (the path arrives as the `arc.contractNegativeFixtures` system property
set in `CodeGeneration/KSP/build.gradle.kts`), compiles it in one pass, requires
`KotlinCompilation.ExitCode.COMPILATION_ERROR`, and asserts that each expected `[ARCKSPxxxx]`
code and each exact message fragment appears. Add your code and message to those assertion
lists in the same change, or the fixture proves nothing.

Java fixtures are not optional decoration. Java is a first-class consumer language, and
several diagnostics exist only because a Java shape can express something Kotlin cannot.

## 4. Prefer a stable compile-time diagnostic over a runtime failure

Every diagnostic identifier starts with the prefix `ARCKSP`, declared in the `ArcDiagnostic`
enum in `ArcDiagnostics.kt`. The reporter emits `[<code>] <message>`, so tests and users match
on the bracketed code. The ranges in use today:

| Range | Area |
| --- | --- |
| `ARCKSP0001` | KSP configuration |
| `ARCKSP0100`–`ARCKSP0109` | Commands, handlers, `provide`, keys, authorization, responses |
| `ARCKSP0200`–`ARCKSP0210` | Read models, queries, routes, infrastructure parameters, defaults |
| `ARCKSP0300`–`ARCKSP0302` | Generated proxy shapes, Jakarta validation metadata, enum wire values |
| `ARCKSP0400` | Java/Kotlin interoperability warning |
| `ARCKSP9999` | Unclassified |

Rules for adding one:

- Append a new enum entry with the next free code in its range. Never renumber an existing
  code and never reuse a retired one; consumers and tests pin the string.
- Call the explicit overload — `logger.error(ArcDiagnostic.QUERY_DEFAULT, message, node)` —
  rather than the single-argument form. The single-argument form routes through
  `ArcDiagnosticReporter.classify(message)`, a fragile prefix/substring match that silently
  degrades to `ARCKSP9999` when the wording drifts.
- Always pass the `KSNode` so the error points at the declaration.
- Choose `Error` when generated code would otherwise be uninvokable or wrong; choose
  `Warning` only for a compilable but risky convention, as `ARCKSP0100` and `ARCKSP0400` do.
- Write the message as an instruction the author can act on, matching the existing voice
  (for example "move handling to a public instance handle function").

Then regenerate the diagnostic reference. `CodeGeneration/KSP/DIAGNOSTICS.md` is checked
against `ArcDiagnostic.referenceMarkdown()` byte-for-byte by `ArcDiagnosticReferenceTest`, so
the file must be updated in the same commit or `:CodeGeneration:KSP:test` fails.

## 5. Regenerate everything downstream

Generated code and manifests flow outward. After the processor change compiles:

```shell
./gradlew :CodeGeneration:KSP:test
./gradlew :ContractTests:test --no-configuration-cache
./gradlew :GradlePlugin:test --no-configuration-cache
./gradlew :GradlePlugin:verifyContractTestProxyDeterminism --no-configuration-cache
```

`:ContractTests:test` covers the generated Kotlin and Java artifacts, the format-5 manifest
(`GeneratedArtifactManifestTest.kt`), and the generated proxy text
(`GeneratedTypeScriptProxiesTest.kt`). If manifest content moved at all, continue with the
**typescript-proxy-contracts** skill — the proxy renderer reads exactly what you changed.

Generated Kotlin has to compile in consumer modules under `allWarningsAsErrors`, so an
unused import, a redundant cast, or a deprecated call in a rendered template breaks the
sample and contract builds rather than the KSP module.

## 6. Update baselines and documentation

- `CodeGeneration/KSP/api/KSP.api` if `ArcSymbolProcessorProvider` moved — nothing else in the
  module is public. Land an `apiDump` in the same commit.
- `Source/api/Source.api` and `GradlePlugin/api/GradlePlugin.api` if a descriptor or the
  manifest reader moved.
- `CodeGeneration/KSP/DIAGNOSTICS.md` for any catalog change.
- `Documentation/reference/annotations.md`, `Documentation/guides/commands.md`, or
  `Documentation/guides/queries.md` when an authoring rule changed for users.

## Verify

```shell
./gradlew :CodeGeneration:KSP:test
./gradlew :ContractTests:test --no-configuration-cache
./gradlew :GradlePlugin:test --no-configuration-cache
./gradlew :GradlePlugin:verifyContractTestProxyDeterminism --no-configuration-cache
./gradlew :ContractTests:typeScriptBuild --no-configuration-cache
./gradlew apiCheck
./Documentation/verify-markdown.sh
```

Done means: the positive fixture compiles and asserts the generated text, the negative fixture
fails compilation with the exact `[ARCKSP…]` code, `DIAGNOSTICS.md` matches the catalog, the
manifest still reads back at the declared `formatVersion`, proxy generation is deterministic
and still compiles strictly, and `.api` baselines are current.
