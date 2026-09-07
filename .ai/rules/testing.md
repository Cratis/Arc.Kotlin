# Testing

This rule governs where tests live, how they are named, which JUnit 5 idioms this repository
actually uses, what belongs in a module's own tests versus `ContractTests`, how the KSP
compile-testing fixtures are structured, and the test-support surface published as
`io.cratis:arc-testing`. It is the depth behind the `AGENTS.md` requirement to use JUnit 5 and to
verify important APIs from both Kotlin and Java.

## Where tests live

| Location | Contains |
| --- | --- |
| `Source/src/test/kotlin/**` | Host-agnostic runtime unit tests |
| `Source/src/test/java/**` | Java conformance tests for the core public surface |
| `Integrations/<Name>/src/test/{kotlin,java}/**` | Integration unit tests, mostly Spring context tests |
| `Testing/src/test/{kotlin,java}/**` | Tests for the published scenario helpers |
| `CodeGeneration/KSP/src/test/kotlin/**` | KSP compile-testing and pure unit tests |
| `GradlePlugin/src/test/kotlin/**` | Manifest discovery, proxy rendering, and differential tests |
| `ContractTests/src/test/{kotlin,java}/**` | Cross-language contracts over generated artifacts |
| `ContractTests/src/testFixtures/{kotlin,java}/**` | Commands, read models, and models KSP processes |
| `ContractTests/src/negativeFixtures/{kotlin,java}/**` | Sources that must **fail** compilation |
| `ContractTests/src/chronicleRealKernelTest/kotlin/**` | Docker-backed kernel gate, outside `check` |
| `ContractTests/TypeScript/contracts/**` | TypeScript type and runtime contracts |

`ContractTests/src/negativeFixtures` is not a compiled Gradle source set. It is read as plain text by
`:CodeGeneration:KSP`'s tests through the `arc.contractNegativeFixtures` system property. Adding a
file there changes the KSP negative test, not the `ContractTests` compilation.

## Naming

Class naming is **not uniform across modules**, and that is the observed state. Match the module you
are editing rather than imposing one style:

- `*Test.kt` in `Source`, `CodeGeneration/KSP`, `GradlePlugin`, and `ContractTests`.
- `*Tests.kt` in all six `Integrations/**` modules and in `Testing`.
- Java: `*Test.java` in `Source`, `ContractTests`, `Integrations/SpringBoot`,
  `Integrations/Chronicle`, `Integrations/Observability`, and `Testing`;
  `*Tests.java` in `Integrations/SpringDataJpa` and `Integrations/SpringDataMongo`.
- Java consumer tests carry an explicit marker in the name — `...JavaConformanceTest`,
  `...JavaContractTest`, or a `Java`-prefixed class — so the Java-side coverage is findable.

This is a repository convention. It is not machine-enforced; consistency within a module is what
matters.

## JUnit 5 idioms used here

Kotlin tests use backticked, lowercase, behavior-describing method names. There are hundreds of them
and they are the house style:

```kotlin
class EndpointRouteHelperTest {
    @Test
    fun `command route uses package and dotnet compatible kebab casing`() {
        val descriptor = CommandDescriptor(
            "AddAuthor",
            "MyApp.Features.Authors.AddAuthor",
            location = listOf("MyApp", "Features", "Authors")
        )
        assertEquals(
            "/api/my-app/features/authors/add-author",
            EndpointRouteHelper.commandRoute(descriptor)
        )
    }
}
```

Java tests are package-private `final class` with `void` camelCase methods:

```java
final class GeneratedArtifactModuleJavaContractTest {
    @Test
    void generatedModuleIsAJavaFriendlyServiceProvider() {
        // ...
    }
}
```

Observed and expected:

- `org.junit.jupiter.api.Test` with statically imported `org.junit.jupiter.api.Assertions.*`
  (`assertEquals`, `assertTrue`, `assertFalse`, `assertThrows`, `assertNull`, `assertSame`).
  Plain JUnit assertions are the default; do not introduce a new assertion DSL.
- `@BeforeEach` / `@AfterEach`, `@TempDir`, and occasionally `@ParameterizedTest` with `@ValueSource`.
- Coroutine tests wrap the body in `runBlocking { }`.
- `@Nested` and `@DisplayName` are **not** used anywhere in this repository. Do not start.
- Mocking is sparse and module-specific: `io.mockk` in `Integrations/Chronicle` and `ContractTests`,
  Mockito (arriving transitively with `spring-boot-starter-test`) in the Spring Boot and Spring Data
  modules. AssertJ is available through the same starter and is used in a few Spring tests.
  Prefer exercising the real pipeline over mocking it — `arc-testing` exists for exactly that.

## Unit tests versus contract tests

Put a test in the owning module when it verifies that module's own behavior in isolation: a route
calculation, a descriptor's equality and immutability, an auto-configuration bean graph, a
processor's naming function, a proxy renderer's output.

Put it in `ContractTests` when it verifies the **public interoperability surface across languages or
across generation stages**:

- Generated command handlers and query performers executing through the real
  `DefaultCommandPipeline` / `DefaultQueryPipeline`.
- The generated `META-INF/cratis/arc/*.json` manifest being deterministic, complete, and
  timestamp-free.
- Kotlin and Java artifacts behaving identically for the same shape.
- Generated TypeScript proxy text, which is asserted from Kotlin in
  `GeneratedTypeScriptProxiesTest` using the `arc.contractTests.generatedProxies` system property.
  That test task depends on `:GradlePlugin:generateContractTestProxies`.

`ContractTests` is unpublished by design. Never move a fixture from it into a published module to
make an import work.

## Java-consumer testing is required for public API changes

Java is a first-class consumer language (`AGENTS.md`). When a change adds or reshapes a public API:

- Add or extend a Java test that calls it the way a Java application would — no Kotlin-only call
  patterns, no `Continuation` parameters, no default-argument reliance.
- Put it next to the existing Java coverage: `Source/src/test/java/io/cratis/arc/conformance/**` for
  the core, the integration's own `src/test/java/**` for a starter,
  `ContractTests/src/test/java/**` for a cross-language contract, and
  `Testing/src/test/java/JavaScenarioConformanceTest.java` for the scenario helpers.
- Update the `.api` baseline in the same change; see [gradle.md](./gradle.md).

A Kotlin-only test for a new public API is an incomplete change.

## KSP compile-testing fixtures

`:CodeGeneration:KSP` tests use `dev.zacsweers.kctfork:ksp` with `useKsp2()`,
`symbolProcessorProviders`, `kspWithCompilation`, and `kspProcessorOptions`. Two kinds of cases exist
and both are required when you change a processor rule.

Positive cases declare their sources inline and assert on generated output:

```kotlin
@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorCommandResponseCompilationTest {
    @Test
    fun `Kotlin and Java aggregates produce ordered response metadata`() {
        val result = compile(listOf(SourceFile.kotlin("ResponseFixtures.kt", """ ... """)))
        // assert on exit code, generated sources, and manifest content
    }
}
```

The negative case is a single test over the whole fixture tree. It walks
`ContractTests/src/negativeFixtures` (supplied as `arc.contractNegativeFixtures` from
`CodeGeneration/KSP/build.gradle.kts`), compiles every `.kt` and `.java` file together, asserts
`KotlinCompilation.ExitCode.COMPILATION_ERROR`, and then asserts that specific `[ARCKSPxxxx]` codes
and specific message fragments appear in `result.messages`. To add a negative rule:

1. Add the offending fixture under `ContractTests/src/negativeFixtures/{kotlin,java}/`.
2. Add its diagnostic code to the assertion list in
   `ArcSymbolProcessorNegativeCompilationTest`, and assert a distinctive message fragment so the
   test fails if the diagnostic degrades into a generic one.
3. Run `./gradlew :CodeGeneration:KSP:test`.

`ArcDiagnosticReferenceTest` asserts that `CodeGeneration/KSP/DIAGNOSTICS.md` is byte-equal to
`ArcDiagnostic.referenceMarkdown()`. Changing the catalog without regenerating that file fails the
test. See [ksp.md](./ksp.md).

## Test fixtures and extra source sets

`ContractTests` applies `` `java-test-fixtures` ``. Fixtures are shared artifacts, not test-only
helpers: KSP is applied to the fixtures source set with `add("kspTestFixtures", project(":CodeGeneration:KSP"))`
and `ksp { arg("arc.moduleName", "ContractTests") }`, so the fixtures are what produce the manifest
the proxy generator reads. A new fixture therefore changes generated TypeScript output — re-run the
proxy gates from [typescript-proxies.md](./typescript-proxies.md).

`chronicleRealKernelTest` is a separately created source set with its own `Test` task. It is
deliberately outside `check`, uses Testcontainers against a digest-pinned Chronicle image, and fails
rather than skips when Docker is unavailable. Do not wire it into `check`.

## The published testing module

`:Testing` (`io.cratis:arc-testing`) is part of the product, not scaffolding. It exposes
`CommandScenario`, `QueryScenario`, `ObservableQueryScenario`, their result types,
`CommandScenarioExtender`, `ScenarioArtifactRegistry`, `ScenarioServiceResolver`, and Java-friendly
blocking and `CompletionStage` facades under `io.cratis.arc.testing.java`. It runs the real
pipelines and does not substitute fake handlers, and JSON round trips are on by default.

Because it is published it has a `.api` baseline (`Testing/api/Testing.api`) and its own Java
conformance test. Treat a change to it as a public API change: design it for both languages, prove
it from Java, and update the baseline deliberately.

## Do not import Cratis .NET testing conventions

This repository does **not** use, and must not adopt:

- `for_` / `when_` / `and_` specification folder or class hierarchies.
- `Cratis.Specifications`, `Should` extensions, or a given/when/then base-class mandate.
- NSubstitute, Moq, or any .NET mocking idiom translated into JVM form.
- One-behavior-per-class specification splitting.

The JVM house style is a plain JUnit 5 class per unit under test, with backticked Kotlin method names
or camelCase Java method names describing behavior. Carrying .NET test structure into this repository
is a defect, not a stylistic preference.
