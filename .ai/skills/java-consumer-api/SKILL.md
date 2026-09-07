---
name: java-consumer-api
description: Use when an Arc.Kotlin API must be proven pleasant and correct from Java, or when a Java consumer reports friction with a public type — read the real JVM signature from the .api baseline, write executable Java that compiles and runs, fix awkwardness in Kotlin with the established bridges, and run the Java-facing gates.
---

# Java consumer API

Java is a first-class consumer language here, not an afterthought. The deliverable of this
workflow is **compiled, executing Java** — never an assertion that Java "should" work.

Invariants live in [`../../rules/java.md`](../../rules/java.md),
[`../../rules/kotlin-java-interop.md`](../../rules/kotlin-java-interop.md) and
[`../../rules/framework.md`](../../rules/framework.md). Read them first; this skill does not
restate them.

Gradle needs a JDK 17 on `JAVA_HOME`/`PATH`. Export it in your shell if `./gradlew` cannot find
one.

## 1. Read the JVM signature, do not guess what Kotlin emits

The `.api` baselines are the ground truth for what Java sees. Start there:

```bash
grep -n "io/cratis/arc/java/JavaAsyncScope" Source/api/Source.api
sed -n '/^public final class io\/cratis\/arc\/commands\/AsyncCommandPipeline {/,/^}/p' Source/api/Source.api
```

Baselines: `Source/api/Source.api`, `CodeGeneration/KSP/api/KSP.api`,
`GradlePlugin/api/GradlePlugin.api`, `Testing/api/Testing.api`, and
`Integrations/<Name>/api/<Name>.api`.

How to read a line:

- `public static final synthetic fun …` — `@JvmSynthetic`; **invisible from Java**. Kotlin-only
  factories such as `AsyncCommandPipeline.Companion.fromCoroutineScope` are marked this way on
  purpose; Java must reach the type another way.
- `public static final field Companion L…$Companion;` — a companion exists. Java calls it as
  `Type.Companion.member(…)` unless the member is `@JvmStatic`, in which case a
  `public static final fun` also appears on the outer class.
- `Ljava/util/concurrent/CompletionStage;` with no type argument in the descriptor is normal —
  generics are erased in the dump. For the real generic shape use `javap` (step 4).

For anything the baseline cannot settle, decompile the built class:

```bash
./gradlew :Source:classes
javap -p -cp Source/build/classes/kotlin/main io.cratis.arc.commands.AsyncCommandPipeline
```

## 2. Decide where the Java check lives

| Surface under test | Java source location |
| --- | --- |
| `Source` public API | `Source/src/test/java/io/cratis/arc/conformance/` |
| `Testing` scenarios | `Testing/src/test/java/io/cratis/arc/testing/` |
| Spring Boot integration | `Integrations/SpringBoot/src/test/java/io/cratis/arc/springboot/` |
| Spring Data, OpenAPI, Observability, Chronicle | `Integrations/<Name>/src/test/java/…` |
| Generated-artifact contracts | `ContractTests/src/test/java/io/cratis/arc/contracts/` |
| Java artifacts KSP must accept | `ContractTests/src/testFixtures/java/io/cratis/arc/contracts/fixtures/` |
| Java artifacts KSP must reject | `ContractTests/src/negativeFixtures/java/io/cratis/arc/contracts/negative/` (driven by `:CodeGeneration:KSP:test`) |
| Consumer-facing end-to-end flow | `Samples/Java/SpringBoot/src/main/java/io/cratis/arc/samples/javaspringboot/` |

## 3. Write Java that compiles and runs

Model it on `Testing/src/test/java/io/cratis/arc/testing/JavaScenarioConformanceTest.java`. It is
both a compile-time conformance check (every awkward call would fail to compile) and a runtime
check (results are asserted). Keep both properties.

Shape to follow:

- JUnit 5, package-private final test class, MIT header.
- Exercise the **short** call forms a real consumer would write, so a missing `@JvmOverloads`
  shows up as a compile error.
- Bridge async through the Java entry point and assert a real value:
  `stage.toCompletableFuture().get(5, TimeUnit.SECONDS)`.
- Close anything `AutoCloseable` with try-with-resources — `JavaAsyncScope`,
  `BlockingCommandScenario`, `BlockingQueryScenario`.
- Java compiles with `-Xlint:all -Werror`, so an unchecked or raw-type warning in your fixture is
  a build failure. That is signal about the API, not noise to suppress.

## 4. Run the interop checklist from Java

Work through each item and record what you observed:

1. **Nullability.** Kotlin platform types reach Java as `@Nullable`/`@NotNull` metadata only —
   Java will not stop you dereferencing. Confirm the contract is what you intend and that
   nullable parameters really accept `null` at runtime.
2. **Default arguments.** Every default-carrying constructor or function a Java consumer calls
   needs `@JvmOverloads` (as on `CommandExecutionOptions` and `ArcApplicationCoroutineScope`).
   Verify the short form compiles from Java, not just from Kotlin.
3. **Static access.** Companion members Java must call need `@JvmStatic`
   (`JavaAsyncScope.usingExecutor`, `CommandExecutionOptions.nested`). Top-level functions get a
   readable holder via `@file:JvmName`, e.g. `CompletionStages`, `ServiceResolvers`,
   `TenantsProviders`.
4. **Suspend and async access.** A `suspend` function is not callable from Java. Java goes
   through `JavaAsyncScope` → `AsyncCommandPipeline` / `AsyncQueryPipeline` /
   `AsyncObservableQueryPipeline` / `AsyncAuthentication`, or through the `Blocking*` scenarios in
   `Testing`. Streaming reaches Java as `java.util.concurrent.Flow.Publisher`, never `Flow`.
5. **Generics and variance.** `CommandResult<*>` surfaces as `CommandResult<?>`; check that the
   Java call site does not need an unchecked cast to be useful.
6. **Boolean property names.** `@get:JvmName("isAuthenticated")` and friends exist so Java reads
   `isAuthenticated()` instead of `getAuthenticated()`. Check new boolean properties.
7. **Exceptions.** Kotlin declares no checked exceptions and this repository uses no `@Throws`.
   Java callers will not be forced to handle a failure — make failure visible in the returned
   result type instead of relying on the compiler.

## 5. Fix awkwardness in Kotlin, do not document a workaround

If the Java call site is ugly, the API is wrong. Change the Kotlin side using an existing bridge:

| Friction | Fix |
| --- | --- |
| Java must supply every optional argument | add defaults plus `@JvmOverloads` |
| Java writes `Type.Companion.x()` | `@JvmStatic` on the companion member |
| A factory is Kotlin-only by design | `@JvmStatic @JvmSynthetic` and a Java path such as `JavaAsyncScope` |
| Java cannot call a `suspend` SPI | implement a `Blocking*`/`Async*` interface plus `*Adapter` in `Source/src/main/kotlin/io/cratis/arc/java/` |
| A Kotlin `Flow` is on the surface | expose `Flow.Publisher` via `CoroutineFlowPublisher`; accept publishers via `asKotlinFlow()` |
| Java hands you a `CompletionStage` | `await()` from `commands/CompletionStageAwait.kt` |
| A top-level function has an ugly `…Kt` holder | `@file:JvmName("…")` |

Never invent a new async bridge alongside these.

## 6. Extend the Java sample when the flow is consumer-facing

`Samples/Java/SpringBoot/src/main/java/io/cratis/arc/samples/javaspringboot/` is the honest test
of "pleasant from Java": records with `@Command`/`@ReadModel`, `CompletionStage` handlers, static
`@Path` query methods, `Flow.Publisher` for observation. Samples consume public starters only —
never reach into project internals. `Documentation/validate-doc-snippets.py` cross-checks these
sample files, so keep them in step with the docs.

## 7. Run the gates

```bash
./gradlew :Source:test
./gradlew :Testing:test
./gradlew :Integrations:SpringBoot:test
./gradlew :ContractTests:test
./gradlew :CodeGeneration:KSP:test
./gradlew :Samples:Java:SpringBoot:test
```

If the public surface moved:

```bash
./gradlew apiCheck
./gradlew :Source:apiDump && git diff -- Source/api/Source.api
```

Then the workspace and documentation gates:

```bash
./gradlew clean build --no-configuration-cache -x :ContractTests:typeScriptRuntimeTest
./Documentation/verify-markdown.sh
```

## Verify

- A Java file you added or changed compiles under `-Xlint:all -Werror` and its assertions run
  green — name the test class and the Gradle task that ran it.
- Every checklist item in step 4 has an observed answer, not an assumed one.
- `./gradlew apiCheck` passes, with any baseline change reviewed and committed alongside the code.
- `./gradlew clean build --no-configuration-cache -x :ContractTests:typeScriptRuntimeTest`
  is clean.
- `./Documentation/verify-markdown.sh` passes if docs or samples changed.
- Report any interop limit you could not remove, and say plainly that it remains.
