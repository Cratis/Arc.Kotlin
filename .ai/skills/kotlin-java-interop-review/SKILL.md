---
name: kotlin-java-interop-review
description: Use when reviewing or designing a public surface that both Kotlin and Java consume, or before publishing a new public type — an inspection workflow that enumerates changed public declarations, reads their real JVM signatures, runs an interop checklist, and produces a findings list classified as blocking, should-fix, or acceptable-with-a-note.
---

# Kotlin/Java interop review

This is an **inspection** workflow. The output is a findings list, not a refactor. Fix nothing
speculatively; verify every candidate fix with compiled Java before you recommend it.

Invariants live in
[`../../rules/kotlin-java-interop.md`](../../rules/kotlin-java-interop.md),
[`../../rules/java.md`](../../rules/java.md) and
[`../../rules/framework.md`](../../rules/framework.md). This skill tells you how to inspect; the
rules say what is correct.

Gradle needs a JDK 17 on `JAVA_HOME`/`PATH`. Export it in your shell first if needed.

## 1. Enumerate the changed public declarations

```bash
git diff --name-only origin/main...HEAD -- '*/src/main/kotlin/**' '*/src/main/java/**'
git diff origin/main...HEAD -- '*/api/*.api'
./gradlew apiCheck
```

If the branch has no baseline diff but source changed, run `./gradlew apiDump` on a scratch
working tree and diff, so you review the real emitted surface rather than the Kotlin text. For a
brand-new type with no baseline yet, list its `public` declarations directly and carry them all
into step 3.

Write the list down. Every entry gets a verdict by the end.

## 2. Read each declaration's real JVM signature

Two sources, in this order:

```bash
git diff origin/main...HEAD -- Source/api/Source.api
```

and, for generic shape, erasure, bridge methods, and synthesized members the baseline elides:

```bash
./gradlew :Source:classes
javap -p -cp Source/build/classes/kotlin/main io.cratis.arc.commands.AsyncCommandPipeline
javap -s -cp Source/build/classes/kotlin/main io.cratis.arc.java.JavaAsyncScope
```

Substitute the module's own output directory for other modules — for example
`Integrations/SpringBoot/build/classes/kotlin/main`, or `build/classes/java/main` for the
Java-authored types such as `io.cratis.arc.springboot.ArcProperties`.

Read Kotlin source only to learn *intent*. Judge the surface from the emitted signature.

## 3. Run the checklist over every declaration

| # | Check | What to look for |
| --- | --- | --- |
| 1 | Naming and mangling | `internal` members leak with a mangled `$module` suffix; top-level functions land in a `…Kt` holder unless `@file:JvmName` is set (`CompletionStages`, `ServiceResolvers`, `TenantsProviders`, `UsersProviders`, `IdentityDetailsProviders`) |
| 2 | Nullability | Kotlin emits `@NotNull`/`@Nullable` metadata that Java does not enforce; a nullable return is a documented contract, not a compiler guarantee |
| 3 | Generics and variance | `<*>` becomes `<?>`; declaration-site variance becomes wildcards; check the Java call site does not need an unchecked cast |
| 4 | Defaults and overloads | a default argument alone emits only a `…$default` synthetic — Java needs `@JvmOverloads` for the short forms (`CommandExecutionOptions`, `ArcApplicationCoroutineScope`, `BlockingCommandScenario`) |
| 5 | Static and companion access | Java writes `Type.Companion.x()` unless `@JvmStatic` also emits a static on the outer class; `@JvmSynthetic` hides a member from Java entirely (`AsyncCommandPipeline.Companion.fromCoroutineScope`) |
| 6 | Suspend access | a `suspend` function is uncallable from Java — it takes a trailing `Continuation`. Require a bridge: `JavaAsyncScope`, `Async*Pipeline`, or a `Blocking*`/`Async*` adapter in `Source/src/main/kotlin/io/cratis/arc/java/` |
| 7 | Async and streaming types | Java-facing async returns `CompletionStage`; streaming returns `java.util.concurrent.Flow.Publisher`. A `kotlinx.coroutines.Flow` or `Deferred` on the surface is a finding |
| 8 | Sealed and enum access | Java can `instanceof`-match a sealed hierarchy (`AsyncObservableQueryOpenResult.Stream`, `CommandExecutionToken`) but cannot extend it, and gets no exhaustiveness. Kotlin enums are ordinary Java enums |
| 9 | Interface default methods | `Source` compiles with `-Xjvm-default=all-compatibility`, so interface bodies become real Java default methods plus a `DefaultImpls` class. A module without that flag emits abstract members Java must implement — check which module the interface lives in |
| 10 | Annotation use sites | verify the target actually landed: `@get:JvmName`, `@get:JsonProperty`, `@get:JvmSynthetic`, `@file:JvmName`. A missing use-site target silently annotates the wrong element |
| 11 | Checked exceptions | Kotlin declares none and this repository uses no `@Throws`, so Java callers are never forced to handle failure. Failure must be visible in the result type |
| 12 | Binary vs source compatibility | an `.api` diff shows binary breakage; source-only breakage (a new required parameter, a widened supertype, a renamed property) can pass `apiCheck` and still break consumers. Judge both |

## 4. Classify every finding

- **Blocking** — Java cannot express the call at all, must cast unsafely, silently gets wrong
  behavior, or the change breaks a published binary contract without a baseline update.
- **Should-fix** — Java works but the call site is noticeably worse than the Kotlin one: missing
  `@JvmOverloads`, `Type.Companion.x()`, a `…Kt` holder name, an avoidable wildcard.
- **Acceptable with a note** — an inherent JVM limit with no better shape available. Say what the
  limit is and where it is documented.

State the reason in the verdict. "Feels awkward" is not a classification.

## 5. Verify a candidate fix with compiled Java before recommending it

Do not recommend `@JvmStatic`, `@JvmOverloads`, `@JvmName`, or a new adapter on reasoning alone.
Add or extend a Java test that fails without the fix and passes with it, then run it:

```bash
./gradlew :Source:test
./gradlew :Testing:test
./gradlew :ContractTests:test
```

Java conformance homes:
`Source/src/test/java/io/cratis/arc/conformance/`,
`Testing/src/test/java/io/cratis/arc/testing/JavaScenarioConformanceTest.java`,
`Integrations/<Name>/src/test/java/…`,
`ContractTests/src/test/java/io/cratis/arc/contracts/`.

Java compiles with `-Xlint:all -Werror`, so an unchecked or raw-type warning your fixture
provokes is itself a finding.

If the fix moves the public surface, confirm the emitted result:

```bash
./gradlew :Source:apiDump && git diff -- Source/api/Source.api
```

## 6. Record interop limits honestly

Some things cannot be annotated away. Say so rather than implying otherwise:

- Java gets no null-safety enforcement from Kotlin nullability metadata.
- Java cannot implement a sealed Kotlin interface and gets no exhaustiveness checking.
- Java cannot call `suspend` functions; a bridge is required, and the bridge changes cancellation
  semantics — `CompletableFuture.cancel` cancels the launched job, and the job's cancellation
  cancels the future.
- `@JvmSynthetic` hides a member from Java but not from reflection or from Kotlin.
- Generic erasure is not repairable by annotation.

If a limit is load-bearing for a consumer, the finding is "document it in `Documentation/`", not
"annotate it".

## 7. Produce the findings list

One entry per declaration you reviewed:

```text
io.cratis.arc.queries.AsyncQueryPipeline.perform
  JVM signature: public final CompletionStage perform(QueryRequest, QueryExecutionOptions)
  Checks failing: 3 (generics) — Java sees QueryResult<?> and casts to read data
  Verdict: should-fix
  Evidence: Source/api/Source.api line N; JavaCoreAdaptersTest requires a cast
  Proposed fix: <fix>, verified by <Java test> under ./gradlew :Source:test
```

Close with the declarations that passed clean, so the reader knows the review was exhaustive.

## Verify

- Every declaration from step 1 appears in the findings list with a verdict and evidence.
- Each verdict cites a real `.api` line or `javap` output, not a reading of the Kotlin source.
- Every recommended fix was compiled and run from Java; name the test and the Gradle task.
- `./gradlew apiCheck` reflects the reviewed state, and any baseline change is deliberate.
- For a full-surface review: `./gradlew clean build --no-configuration-cache -x :ContractTests:typeScriptRuntimeTest`.
- Name every declaration you could not verify and why.
