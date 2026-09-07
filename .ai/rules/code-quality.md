# Code Quality

This file governs the language-independent quality bar for every change in this repository —
Kotlin, Java, Gradle build scripts, generated-code templates, and TypeScript under `ContractTests`.
It is deliberately short and does not restate language style: Kotlin conventions live in
[kotlin.md](./kotlin.md), Java conventions in [java.md](./java.md), dual-language public shape in
[kotlin-java-interop.md](./kotlin-java-interop.md), and framework design in
[framework.md](./framework.md).

## Read before you write

Before adding to a file or package, read enough of what is around it to match it. This repository is
internally consistent on purpose, and the patterns are load-bearing: `Default*` for the built-in
implementation of a contract (`DefaultCommandPipeline`, `DefaultQueryRenderers`), `Concurrent*` for a
thread-safe registry (`ConcurrentCommandHandlerRegistry`), `Arc*` for a host-facing type
(`ArcAutoConfiguration`, `ArcPrincipal`), constructor parameters defensively copied with
`java.util.List.copyOf`, and KDoc on every public declaration stating the contract. A change that
introduces a second way of doing something already solved costs more than it saves.

The corollary: when the surrounding code is wrong, fix it in a separate, clearly scoped commit
rather than adding an inconsistency and rationalizing it.

## Make the smallest change that fully solves the problem

- **Smallest** — do not refactor adjacent code, rename unrelated symbols, reformat untouched lines,
  or "improve" a neighboring file while fixing a bug. Unrelated churn hides the real change from
  review and from `git blame`.
- **Fully** — do not stop at the symptom. If the bug reaches the generated proxy, the manifest, and
  the OpenAPI schema, fix all three; if a public contract moved, update the `.api` baseline and the
  documentation in the same change.
- These pull in opposite directions on purpose. The correct change is the complete fix and nothing
  else.

## No dead code, no speculative generality

- Delete code rather than commenting it out. Version control is the archive; a commented-out block
  is noise that outlives its author's reason for keeping it.
- Do not add a parameter, overload, type parameter, interface, or configuration property because it
  "might be needed". In a framework repository every such addition lands in an `.api` baseline and
  becomes a contract you must keep.
- Do not add an abstraction with one implementation and no second caller in sight. Prefer the
  focused type — see the specialization principle in [framework.md](./framework.md).
- Remove an unused private helper, an unreferenced fixture, or a superseded code path as part of the
  change that supersedes it.

## Name things meaningfully

- Names state what the thing *is* or *does* in the domain of the framework: `QueryPerformerRegistry`,
  `GuardObservableQueryEmission`, `ObservableQueryTransferMode`, `TenantResolutionContext`. Avoid
  `data`, `info`, `helper`, `manager`, `util`, and single letters outside a short lambda or a type
  parameter.
- A boolean reads as a predicate (`allowsAnonymous`, `supportsPaging`, `isAuthenticated`).
- Keep one name for one concept across the whole stack. `correlationId` is `correlationId` in
  `CommandContext`, `CommandResult`, the JSON envelope, the `X-Correlation-ID` header, and the
  generated client. Introducing a synonym for an existing concept is a defect.
- Use American English everywhere: `behavior`, `serialize`, `initialize`, `color`, `canceled`.

## Comment why, never what

- Do not narrate the code. `// increment the counter` is noise; the signature and the KDoc contract
  carry the meaning.
- Write a comment when the reason is not derivable from the code — a workaround, an ordering
  constraint, an interaction with a tool. Two real examples from this repository:
  `// Keeps the Java source package visible while KSP-generated Kotlin is compiled in the same source set.`
  in `Samples/Java/SpringBoot/src/main/kotlin/io/cratis/arc/samples/javaspringboot/PackageMarker.kt`,
  and `// Generated Kotlin handlers reference the Java task-board types, so make those types available before Kotlin compilation.`
  in that sample's `build.gradle.kts`.
- Public KDoc states the contract — ordering, nullability, thread-safety, failure behavior — not the
  implementation.
- Do not leave `TODO`/`FIXME` in place of behavior a public API claims to provide; that is the
  placeholder prohibition in [framework.md](./framework.md), not a comment style question.
- Every source and build file starts with the standard Cratis MIT header.

## Error messages tell the caller what to do

An error is a user interface. The established quality bar here is high, and new messages must match
it:

- Name the offending value and the requirement: `"coroutineParallelism must be greater than zero."`,
  `"queueCapacity cannot be negative."`, `"correlationHeader must not be blank."`
- Name every artifact involved in a conflict, so the reader does not have to search:
  `"Exactly one Arc identity details provider may be registered; found 2"` followed by both provider
  class names, and `"Duplicate Arc POST route '/api/duplicates/same-command'"` followed by both
  command types.
- Fail at the earliest point that can detect the problem. Property setters validate at binding time
  so the application context fails at startup rather than on the first request; KSP fails the
  compilation with a stable `ARCKSP` code rather than deferring to runtime.
- Caller-facing failures stay machine-readable. A rejected command or query becomes a
  `ValidationResult` with a `ValidationResultSeverity`, a member path, and a `ValidationResultReasons`
  value (`rule`, `malformedRequest`, `dependencyUnavailable`, `validatorFailed`,
  `constraintViolation`, `concurrencyViolation`) — not a bare string in an exception message.
- Never leak internals to a client. `ExceptionDetailRedactor` produces client-safe results while the
  host logs the full detail; keep both halves.

## Do not swallow exceptions

- An exception must produce an outcome the caller can see: a `CommandResult`/`QueryResult`, a
  validation result, a thrown exception with more context, or a logged failure plus a redacted
  response. `DefaultQueryValidationFilter` is the model — a validator that throws becomes an error
  `ValidationResult` with reason `validatorFailed`, so the caller learns that validation could not be
  completed.
- An empty `catch`, a `catch` that only logs at `debug`, and a bare `runCatching { }.getOrNull()`
  whose `null` then flows on unnoticed are all prohibited. A recovery is acceptable only when the
  fallback *is* the documented contract at that exact call site and is narrow — for example
  `runCatching { Class.forName(...) }.getOrNull() ?: return null` in
  `ArcValidationAutoConfiguration`, where "no reflective executable found" explicitly means "skip
  executable validation".
- Never catch `Throwable`, and never catch `CancellationException` without rethrowing it —
  cancellation must keep propagating through coroutine code.
- Do not convert a real failure into a success-shaped default so a gate goes green.

## Never suppress a diagnostic to reach green

- Kotlin compiles with `allWarningsAsErrors`, and Java with `--release 17 -Xlint:all -Werror`. A
  warning is a build failure, and the fix is the code — not a suppression, not a compiler flag, not
  narrowing the lint set.
- `@Suppress`/`@SuppressWarnings` is a last resort with the narrowest possible scope, on the single
  declaration or statement, and only where the compiler cannot see an invariant the code guarantees.
  The existing uses are of exactly that shape: `@Suppress("UNCHECKED_CAST")` on one cast whose safety
  a preceding type check establishes, in `QueryRenderers`, `ReadModelInterceptors`, and `QueryResult`.
  File-level or module-level suppression is not acceptable.
- The same applies to gates: do not exclude a failing test, relax an assertion, regenerate an `.api`
  baseline you did not intend to change, or add a normalization to a comparison so it passes. When a
  gate fails, either the code is wrong or the gate's expectation genuinely changed — decide which,
  and say so.
- Never silence a `ARCKSP` diagnostic by weakening the check. Fix the model, or add a supported
  shape.

## Definition of a quality change

Before calling work complete, confirm all of the following against a real signal rather than
self-assessment (the gates and commands are in [general.md](./general.md)):

1. The affected modules build with zero warnings and zero errors.
2. Tests for the affected modules pass, including `ContractTests` when a public contract moved.
3. `.api` baselines reflect intended public changes and nothing else.
4. Documentation under `Documentation/` matches the new behavior, and its verification passes.
5. No dead code, commented-out code, placeholder, or stub was introduced.
6. What you did *not* verify is stated in the report.
