---
name: kotlin-framework-development
description: Use when implementing or changing framework behavior in Kotlin anywhere under Source, CodeGeneration/KSP, GradlePlugin, Integrations or Testing — walks module placement, public surface design, coroutine and cancellation handling, Kotlin and Java tests, the .api baseline, Documentation, and the gates that must pass.
---

# Kotlin framework development

Arc.Kotlin is a library. Every change you make here is somebody else's public API, in two
languages. Follow this procedure in order; do not skip step 2 or step 7.

Invariants live in the rules and are not repeated here — read
[`../../rules/framework.md`](../../rules/framework.md),
[`../../rules/kotlin.md`](../../rules/kotlin.md),
[`../../rules/kotlin-java-interop.md`](../../rules/kotlin-java-interop.md) and
[`../../rules/gradle.md`](../../rules/gradle.md) before starting. If a rule and this skill
disagree, the rule wins.

Gradle needs a JDK 17 on `JAVA_HOME`/`PATH`. If `./gradlew` cannot find one, export it in your
shell before running any command below.

## 1. Locate the module and respect dependency direction

Read `settings.gradle.kts` for the module list, then the target module's `build.gradle.kts` for
what it may see:

```bash
grep -n "project(\":" Source/build.gradle.kts Integrations/SpringBoot/build.gradle.kts
```

Decision point — where does the change belong?

- Host-agnostic behavior (pipelines, results, metadata, validation, concepts) → `Source`.
  `Source/build.gradle.kts` declares Jackson, coroutines, jakarta-validation, kotlin-reflect and
  slf4j only. **A Spring import in `Source` is an architecture violation, not a build error to
  fix with a dependency.**
- Auto-configuration, HTTP/WebSocket transport, properties, bean wiring →
  `Integrations/SpringBoot`, where web, websocket, security and validation stay `compileOnly` so
  they remain optional for consumers.
- Compile-time artifact discovery or generated code → `CodeGeneration/KSP`.
- Reusable scenario/test support → `Testing` (depends on `Source` only).

## 2. Read the surrounding area first and match its pattern

Consistency beats personal preference here. Before adding a file, read its neighbors:

```bash
ls Source/src/main/kotlin/io/cratis/arc/commands/
```

The command pipeline is the reference shape for a feature, end to end:

| Concern | File |
| --- | --- |
| Public SPI | `Source/src/main/kotlin/io/cratis/arc/commands/CommandPipeline.kt` |
| Default implementation | `Source/src/main/kotlin/io/cratis/arc/commands/DefaultCommandPipeline.kt` |
| Host-supplied options | `Source/src/main/kotlin/io/cratis/arc/commands/CommandExecutionOptions.kt` |
| Validation filter | `Source/src/main/kotlin/io/cratis/arc/commands/DefaultCommandValidationFilter.kt` |
| Result type | `Source/src/main/kotlin/io/cratis/arc/results/CommandResult.kt` |
| Java bridge | `Source/src/main/kotlin/io/cratis/arc/commands/AsyncCommandPipeline.kt` |
| Kotlin tests | `Source/src/test/kotlin/io/cratis/arc/commands/CommandPipelineTest.kt` |
| Java conformance | `Source/src/test/java/io/cratis/arc/conformance/JavaCoreAdaptersTest.java` |
| Generated artifacts | `<module>/build/generated/ksp/**/io/cratis/arc/generated/<Module>ArcArtifactModule.kt` |
| Spring wiring | `Integrations/SpringBoot/src/main/kotlin/io/cratis/arc/springboot/ArcAutoConfiguration.kt` |
| Documentation | `Documentation/guides/commands.md` |

Your change almost certainly touches more than one of those rows. Note which before you write code.

## 3. Design the public surface before implementing

Write the signatures first, then fill them in.

- **Defaults.** Give optional parameters real defaults and add `@JvmOverloads` so Java gets the
  short forms too — `CommandExecutionOptions` does exactly this for `tenantId`,
  `tenantNamespace`, `allowedValidationSeverity` and `exposeExceptionDetails`.
- **Override points.** Publish an `interface` for the behavior and a `Default*` class that
  implements it, then bind it in Spring under `@ConditionalOnMissingBean` so a consumer can
  replace it without forking.
- **Factories.** Companion factories that Java must call need `@JvmStatic`
  (`CommandExecutionOptions.nested`, `JavaAsyncScope.usingExecutor`). A factory that only Kotlin
  hosts should call is `@JvmStatic @JvmSynthetic`, as on
  `AsyncCommandPipeline.Companion.fromCoroutineScope`.
- Mark every declaration `public`/`internal` explicitly, one top-level declaration per file, MIT
  header at the top.

## 4. Handle concurrency, cancellation and context propagation explicitly

- Suspend at the SPI boundary. `CommandPipeline.execute` and `QueryPipeline.perform` are
  `suspend`; keep new SPIs suspending rather than returning futures from Kotlin.
- Per-execution state travels in a `CoroutineContext` element, never a `ThreadLocal`.
  `Source/src/main/kotlin/io/cratis/arc/commands/CommandExecutionToken.kt` defines
  `CommandExecutionContext : AbstractCoroutineContextElement(Key)`, and `DefaultCommandPipeline`
  reads it with `currentCoroutineContext()[CommandExecutionContext]?.token` and establishes it
  with `withContext(CommandExecutionContext(token))`.
- If a third-party library genuinely is thread-bound, bridge it with a
  `kotlinx.coroutines.ThreadContextElement` as
  `Integrations/Observability/src/main/kotlin/io/cratis/arc/observability/springboot/ArcObservationRecorder.kt`
  does — do not leak a raw `ThreadLocal` into framework state.
- Cleanup that must still run after cancellation goes in `withContext(NonCancellable) { … }`
  (see `DefaultCommandPipeline.kt`). Everything else must stay cancellable.
- Never `GlobalScope`. Host work runs on the bounded
  `Integrations/SpringBoot/src/main/kotlin/io/cratis/arc/springboot/ArcApplicationCoroutineScope.kt`;
  Java callers own their scope through `JavaAsyncScope`.

## 5. Implement, reusing the established bridges

Do not invent a new async bridge. The contracts already exist:

| Need | Reuse |
| --- | --- |
| Java owns a scope for async facades | `io.cratis.arc.java.JavaAsyncScope` (`AutoCloseable`) |
| Suspend pipeline → `CompletionStage` | `AsyncCommandPipeline`, `AsyncQueryPipeline`, `AsyncObservableQueryPipeline`, `AsyncAuthentication` |
| `CompletionStage` → suspend | `CompletionStage<T>.await()` in `commands/CompletionStageAwait.kt` (`@file:JvmName("CompletionStages")`) |
| JDK `Flow.Publisher` → Kotlin `Flow` | `queries/JdkPublisherFlow.kt` `asKotlinFlow()` |
| Kotlin `Flow` → JDK `Flow.Publisher` | `internal class CoroutineFlowPublisher` in `java/AsyncObservableQueryPipeline.kt` |
| Java SPI implementations | the `Blocking*`/`Async*` interfaces and `*Adapter` classes in `Source/src/main/kotlin/io/cratis/arc/java/` |
| Java tests of scenarios | `Testing/src/main/kotlin/io/cratis/arc/testing/java/` |

## 6. Add Kotlin tests, plus a Java-facing check when the surface is public

- Kotlin: `<Module>/src/test/kotlin/io/cratis/arc/<area>/<Thing>Test.kt`, JUnit 5.
- Java: a conformance test under `Source/src/test/java/io/cratis/arc/conformance/` (or the
  integration module's `src/test/java`) that *compiles and runs* against the new surface.
  `JavaScenarioConformanceTest` in `Testing/src/test/java/io/cratis/arc/testing/` is the model.
- If a generated contract or proxy shape moved, extend `ContractTests` too. See
  [`../../rules/testing.md`](../../rules/testing.md).

## 7. Update the `.api` baseline deliberately

```bash
./gradlew :Source:apiCheck
./gradlew :Source:apiDump && git diff -- Source/api/Source.api
```

Baselines are checked in for `Source`, `CodeGeneration/KSP`, `GradlePlugin`, `Testing` and all
six integrations; `ContractTests` and `Samples` are excluded in the root `apiValidation` block.
Read every line of the diff — an unexpected entry means the surface moved further than you
intended. Land the dump in the same commit as the change.

## 8. Update Documentation

Public-facing behavior updates the matching page under `Documentation/` (for the command
pipeline, `Documentation/guides/commands.md`). `Documentation/validate-doc-snippets.py`
cross-checks documented framework symbols against real source and the runnable samples, so a
renamed symbol breaks the docs gate. See
[`../../rules/documentation.md`](../../rules/documentation.md).

## 9. Run the gates

```bash
./gradlew :Source:test
./gradlew apiCheck
./gradlew clean build --no-configuration-cache -x :ContractTests:typeScriptRuntimeTest
./Documentation/verify-markdown.sh
```

Add the proxy and TypeScript runtime gates only when generated proxies could have moved:

```bash
./gradlew :GradlePlugin:verifyContractTestProxyDeterminism :ContractTests:typeScriptBuild --no-configuration-cache
./gradlew :ContractTests:typeScriptRuntimeTest --no-configuration-cache
```

## What makes this change wrong

- A warning anywhere in the affected modules. Kotlin runs `allWarningsAsErrors`, Java runs
  `-Xlint:all -Werror`; suppressing the warning to get green is not fixing it.
- A placeholder, no-op stub, or fake implementation added so a gate passes.
- A Spring, Chronicle, or host-framework type reachable from `Source`.
- `ThreadLocal` holding state that a coroutine is expected to observe.
- A new one-off `CompletableFuture`/`Flow` bridge next to the ones in step 5.
- An `.api` diff you did not read, or an `apiDump` committed separately from the change.
- Claiming Arc .NET parity that no test, contract test, or runnable sample demonstrates — see
  [`../../rules/arc-parity.md`](../../rules/arc-parity.md).

## Verify

- `./gradlew clean build --no-configuration-cache -x :ContractTests:typeScriptRuntimeTest`
  passes with zero warnings and zero errors.
- `./gradlew apiCheck` passes, and any `.api` change is a reviewed, intentional diff in the same
  commit.
- The new Kotlin test fails against the old behavior and passes against the new one.
- A Java test exercises any surface a Java consumer can reach.
- `./Documentation/verify-markdown.sh` passes when documentation changed.
- The proxy determinism and TypeScript runtime gates pass when generated output could have moved.
- Report which gates you ran and name anything you did not verify.
