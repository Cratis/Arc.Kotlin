---
name: jvm-testing
description: Use when adding or fixing a test in Arc.Kotlin — choosing the level (module unit test, Java conformance, Testing scenario, ContractTests, KSP compile fixture, or sample), placing and naming it the way this repository already does, running the focused test or the full gate, and diagnosing a failure without weakening it.
---

# JVM testing

Invariants for test layout, naming, and JUnit 5 usage live in [testing.md](../../rules/testing.md);
language conventions live in [kotlin.md](../../rules/kotlin.md) and [java.md](../../rules/java.md).
This skill is the procedure. Gradle needs JDK 17 on the PATH — confirm with `java -version` before
any `./gradlew` command, and export `JAVA_HOME` for your own JDK 17 installation if it is missing.
Never write a machine-specific JDK path into a committed file.

## 1. Choose the level

Pick the cheapest level that can actually fail when the behavior regresses.

| Level | Use it when | Lives in |
| --- | --- | --- |
| Module unit test | Behavior inside one module, no host needed | `Source/src/test/kotlin/`, `Integrations/<X>/src/test/kotlin/`, `CodeGeneration/KSP/src/test/kotlin/`, `GradlePlugin/src/test/kotlin/` |
| Java conformance test | A public surface changed and must stay pleasant from Java | `Source/src/test/java/io/cratis/arc/conformance/`, `Integrations/<X>/src/test/java/` |
| Spring context test | Auto-configuration, bean wiring, HTTP hosting | `Integrations/<X>/src/test/kotlin/` using `ApplicationContextRunner`, `@SpringBootTest`, `@AutoConfigureMockMvc` |
| `Testing` scenario support | The `arc-testing` scenario API itself changed | `Testing/src/test/kotlin/`, `Testing/src/test/java/` |
| ContractTests | A generated artifact or a cross-language consumer contract moved | `ContractTests/src/test/kotlin/`, `ContractTests/src/test/java/`, fixtures in `ContractTests/src/testFixtures/{kotlin,java}/` |
| KSP compile test | Processor output, generated metadata, or an `ARCKSP` diagnostic | `CodeGeneration/KSP/src/test/kotlin/`, invalid fixtures in `ContractTests/src/negativeFixtures/{kotlin,java}/` |
| Sample test | End-to-end runnable behavior a documentation page quotes | `Samples/{Kotlin,Java}/{SpringBoot,ChronicleSpringBoot}/src/test/` |
| Chronicle real kernel | Only a real pinned Chronicle kernel container can prove it | `ContractTests/src/chronicleRealKernelTest/kotlin/` |

If a public contract moved, a module unit test is not enough on its own — add the `ContractTests`
and Java-side coverage as well.

## 2. Place and name it

1. Put the test in the same package as the code it exercises, mirroring the main source tree.
2. Match the suffix used by the module you are in — it is not uniform, and consistency inside a
   module wins:
   - `*Test.kt` / `*Test.java` in `Source` (33 files), `CodeGeneration/KSP` (10), `ContractTests` (9),
     `GradlePlugin` (1).
   - `*Tests.kt` / `*Tests.java` in `Integrations/*`, `Testing`, and `Samples`.
   - Java conformance tests in `Source` are named `<Area>JavaConformanceTest.java` and sit in the
     `io.cratis.arc.conformance` package.
3. Start every file with the standard Cratis MIT header (two `//` lines).
4. Declare Kotlin test classes plain or `internal`; declare Java test classes package-private
   (`final class SomethingTest {`). No test class in this repository is `public`.

## 3. Write the test body

- Assertions are JUnit Jupiter only: `org.junit.jupiter.api.Assertions.assertEquals`, `assertTrue`,
  `assertFalse`, `assertNull`, `assertSame`, `assertThrows`, imported one by one. Do not add a new
  assertion library.
- AssertJ (`org.assertj.core.api.Assertions.assertThat`) is used only where
  `spring-boot-starter-test` brings it for Spring context assertions — see
  `Integrations/Chronicle/src/test/kotlin/io/cratis/arc/chronicle/springboot/ChronicleArcAutoConfigurationTests.kt`.
- Mocking: MockK 1.13.14 is declared in `Integrations/Chronicle/build.gradle.kts` and
  `ContractTests/build.gradle.kts`. Mockito arrives with `spring-boot-starter-test` and is used in
  `Integrations/SpringDataJpa` and `Integrations/SpringDataMongo`. Prefer a real fixture object over a
  mock; add a mocking library to a module that does not already have one only with a stated reason.
- Kotlin test names are backticked sentences describing observable behavior, and suspend code is
  wrapped in `runBlocking`:

  ```kotlin
  @Test
  fun `single model is returned as single data`() = runBlocking {
      val result = pipeline(TestPerformer(name) { model }).perform(request, options)

      assertSame(model, result.data)
      assertTrue(result.isSuccess)
  }
  ```

- Java test names are lowerCamelCase sentences —
  `Testing/src/test/java/io/cratis/arc/testing/JavaScenarioConformanceTest.java` is the model to copy.
- JUnit is wired centrally: the root `build.gradle.kts` adds `org.junit.jupiter:junit-jupiter:5.11.4`,
  `junit-platform-launcher`, and `useJUnitPlatform()` to every project with the `java` plugin. Do not
  re-declare them in a module build file.
- Kotlin compiles with `allWarningsAsErrors` and Java with `-Xlint:all -Werror`, tests included. Fix
  the warning; never add a suppression to get a test compiling.

## 4. Write the Java-side test when a public surface changed

Java is a first-class consumer, so a changed public API needs a test that calls it as Java sees it —
no Kotlin default arguments, no extension functions, no `Continuation` in the signature.

1. Add or extend the conformance test next to the surface: `Source/src/test/java/io/cratis/arc/conformance/`
   for core types, `Integrations/<X>/src/test/java/` for an integration,
   `ContractTests/src/test/java/io/cratis/arc/contracts/` for a generated artifact contract.
2. Exercise the builder/overload the Java caller would actually reach, plus the blocking and
   `CompletionStage` bridges where they exist (`BlockingCommandScenario`, `AsyncCommandScenario`).
3. If the change moved a published API shape, update the checked-in `.api` baseline in the same
   change with `./gradlew apiDump`, then confirm with `./gradlew apiCheck`.

## 5. Add KSP compile fixtures

KSP tests use `dev.zacsweers.kctfork:ksp:0.7.0`. Every KSP test class carries a private `compile`
helper that sets `useKsp2()`, `inheritClassPath = true`,
`symbolProcessorProviders = mutableListOf(ArcSymbolProcessorProvider())`, `kspProcessorOptions`
with `arc.moduleName`, and `kspWithCompilation = true`.

**Positive fixture** — the source is inline in the test:

1. Build `SourceFile.kotlin("Name.kt", """…""")` or `SourceFile.java(...)` for the valid shape.
2. Assert `KotlinCompilation.ExitCode.OK`, passing `result.messages` as the assertion message so a
   failure prints the compiler output.
3. Assert on what was generated — the loaded `ArcArtifactModule`, a class from `result.classLoader`,
   or a generated resource such as `ksp/sources/resources/META-INF/cratis/arc/MapMetadata.json`.
   For determinism, compile twice into two temp directories and byte-compare the resource, as
   `ArcSymbolProcessorMapCompilationTest` does.

**Negative fixture** — the source is a real file:

1. Add the invalid type under `ContractTests/src/negativeFixtures/kotlin/io/cratis/arc/contracts/negative/`
   or the sibling `java/` tree. `CodeGeneration/KSP/build.gradle.kts` passes that directory to the
   test as the `arc.contractNegativeFixtures` system property.
2. `ArcSymbolProcessorNegativeCompilationTest` walks the whole tree, compiles every `.kt` and `.java`
   in it together, and asserts `ExitCode.COMPILATION_ERROR`.
3. Add the expected `ARCKSP####` code and its exact message text to the lists in that test. A fixture
   with no asserted diagnostic proves nothing.
4. A warning-only diagnostic is asserted the other way round: `ExitCode.OK` plus the code in
   `result.messages` (see the `ARCKSP0100` case).

## 6. Run it

Focused runs, cheapest first:

```bash
./gradlew :Source:test --tests "io.cratis.arc.queries.QueryPipelineTest"
./gradlew :Source:test --tests "io.cratis.arc.queries.QueryPipelineTest.single model is returned as single data"
./gradlew :CodeGeneration:KSP:test --tests "*ArcSymbolProcessorNegativeCompilationTest"
./gradlew :Integrations:SpringBoot:test
./gradlew :Testing:test
./gradlew :ContractTests:test
```

Quote the whole `--tests` value: Kotlin backticked test names contain spaces and the filter matches
them literally. `:ContractTests:test` first runs `:GradlePlugin:generateContractTestProxies`, so it is
slower than a plain module test; `:ContractTests:check` additionally pulls in
`typeScriptRuntimeTest`, so do not reach for `check` on that module casually.

The gate, once the focused test is green:

```bash
./gradlew clean build --no-configuration-cache -x :ContractTests:typeScriptRuntimeTest
./gradlew apiCheck
```

Generated proxies, the TypeScript gates, and `:ContractTests:chronicleRealKernelTest` (needs Docker
and the pinned Chronicle image) are separate — see [typescript-proxies.md](../../rules/typescript-proxies.md)
and [gradle.md](../../rules/gradle.md) for when they apply.

## 7. Diagnose a failure without weakening it

1. Read the assertion message first. KSP tests pass `result.messages` as the message, so the whole
   compiler output is already in the failure text.
2. Open the report: `<Module>/build/reports/tests/test/index.html`, raw XML in
   `<Module>/build/test-results/test/`.
3. Re-run only the failing test with `--tests`, adding `--info` or `--stacktrace` when the cause is
   still unclear.
4. Decide which side is wrong. Change the production code when behavior regressed; change the
   expectation only when you can say why the old expectation was wrong, and say it in the test name
   or the pull request.
5. Re-run the gate that failed. A green focused test does not clear a full-build failure.

## 8. Never do these

- **Never import Cratis .NET testing conventions.** There is no `for_`/`when_` folder mandate, no
  `Cratis.Specifications`, and no NSubstitute in this repository. Tests are plain JUnit 5 classes in
  the package of the code they exercise.
- **Never add a stub, no-op, fake implementation, or placeholder to make a test or build pass.**
  `AGENTS.md` forbids it; a passing gate over fake behavior is worse than a red one.
- Never relax an assertion, delete a test, or add `@Disabled` to get to green.
- Never suppress a compiler warning or turn off `allWarningsAsErrors` / `-Werror` for a test.
- Never claim a test proves Arc .NET parity unless it actually exercises the behavior — see
  [arc-parity.md](../../rules/arc-parity.md).

## Verify

```bash
java -version                                    # must report 17
./gradlew :<Module>:test --tests "<FullyQualifiedTestClass>"
./gradlew clean build --no-configuration-cache -x :ContractTests:typeScriptRuntimeTest
./gradlew apiCheck                               # if any public API shape moved
```

Done means: the new test fails against the unfixed code and passes against the fix, every affected
module builds with zero warnings, `.api` baselines match if a public shape moved, and you can name
each command you actually ran. Report what you did not run.
