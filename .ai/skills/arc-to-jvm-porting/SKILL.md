---
name: arc-to-jvm-porting
description: Use when porting a feature, API, or behavior from the Arc .NET repository into this JVM repository, or when deciding whether a parity claim in Documentation/reference/parity.md may be strengthened — it pins the .NET source, characterizes behavior, places the work in the right module, and proves it before any status changes.
---

# Port a behavior from Arc .NET to Arc.Kotlin

Arc .NET is the `Cratis/Arc` repository, read from a **read-only** local checkout — commonly a
sibling directory, `../Arc`. Read it freely. Never create, edit, or delete a file there, never run
its builds, and never commit anything into it. Set `ARC` once and use it in every command below,
so nothing here depends on where your checkout happens to live:

```shell
ARC=../Arc            # or wherever your read-only Cratis/Arc checkout is
git -C "$ARC" rev-parse --show-toplevel
```

Porting is not transliteration. The deliverable is *observable behavior* reproduced with an
idiomatic JVM shape and proven by executable checks — not C# translated into Kotlin. Parity
invariants are owned by [`../../rules/arc-parity.md`](../../rules/arc-parity.md); framework
design by [`../../rules/framework.md`](../../rules/framework.md); interop by
[`../../rules/kotlin-java-interop.md`](../../rules/kotlin-java-interop.md). This file is the
procedure.

## 1. Pin the source

Record the exact .NET revision you read, before reading anything else:

```shell
git -C "$ARC" rev-parse HEAD
git -C "$ARC" describe --tags
git -C "$ARC" rev-parse --abbrev-ref HEAD
```

There is no version file to cite: `Source/DotNET/Directory.Build.props` hardcodes a
placeholder `1.0.0`, and real package versions are injected from release tags by
`.github/workflows/publish.yml`. The sha plus `describe --tags` output *is* the pin. Put it in
your notes, in the PR body, and in any parity edit that results.

Locate four artifacts, not one — implementation, its specs, its documentation, and its
generated or wire contract:

| What you are looking for | Where it lives under `$ARC` |
| --- | --- |
| Host-agnostic core | `Source/DotNET/Arc.Core/` — `Commands/`, `Queries/`, `Validation/`, `Authorization/`, `Authentication/`, `Identity/`, `Tenancy/`, `Execution/`, `Http/`, `Introspection/`, `OpenApi/`, `DependencyInjection/` |
| ASP.NET host integration | `Source/DotNET/Arc/` — `ModelBinding/`, `Http/`, `Commands/`, `Queries/`, `Queries/ControllerBased/` |
| Chronicle | `Source/DotNET/Chronicle/` — `Aggregates/`, `Commands/`, `Reactors/`, `ReadModels/`, `Tenancy/` |
| Persistence seams | `Source/DotNET/MongoDB/`, `Source/DotNET/EntityFrameworkCore/` |
| Roslyn analyzers and generators | `Source/DotNET/Arc.Core.CodeAnalysis/`, `Source/DotNET/Arc.Core.Generators/` |
| TypeScript proxy generator | `Source/DotNET/Tools/ProxyGenerator/` with Handlebars templates in `Templates/` (`Command.hbs`, `Query.hbs`, `ObservableQuery.hbs`, `Type.hbs`, `Enum.hbs`, `Interface.hbs`) |
| TypeScript client packages | `Source/JavaScript/Arc/` (`@cratis/arc`), `Source/JavaScript/Arc.React/` |
| Specs | a sibling `<Project>.Specs` directory, laid out as `for_<Type>/when_<behavior>/and_<condition>.cs` |
| Checked-in generated proxies | `TestApps/AspNetCore/**/*.ts` and `TestApps/Shared/*.ts`, emitted in place next to the `.cs` that produced them |
| Documentation | `Documentation/backend/**` and `Documentation/frontend/**` |

Two traps worth knowing:

- `Source/DotNET/Arc.Core.Generators/` is a C# metadata generator (two files), **not** the
  TypeScript emitter. Generated TypeScript comes from the reflection-based
  `Tools/ProxyGenerator` CLI and its Handlebars templates.
- `$ARC/.ai-work/` contains scratch worktrees that mirror
  `Source/DotNET` and `TestApps`. Never cite a path under it; it is not authoritative.

The `TestApps` proxies are the most useful single fixture for a wire question: the `.ts` and
the `.cs` that produced it sit side by side and are both in git.

## 2. Characterize observable behavior before translating syntax

The deliverable of this step is **a written behavior description**, kept in `.ai-work/`, not a
code sketch. For each item below, state the .NET behavior and cite the file and the spec that
proves it. Where nothing proves it, write "unspecified in .NET" — do not guess, and do not let
a plausible reading become a parity claim later.

- **Ordering** — declaration order, registration order, filter order, emission order. Arc
  flattens aggregate responses and provided values in declaration order; order is contract.
- **Defaults versus explicit null** — does an absent value execute a default, or arrive as
  null? On the JVM these are different states; see the honesty boundaries below.
- **Validation and authorization failure shapes** — severity thresholds, envelope fields,
  whether the handler runs at all, and what a `validate` route returns.
- **Cancellation** — what a cancelled request does to work already started, to staged effects,
  and to open subscriptions.
- **Context and tenancy propagation** — what the .NET ambient accessors
  (`CommandContextManager`, `ICommandContextAccessor`) carry, and which of it must become an
  explicit parameter here.
- **Error and status mapping** — exception to HTTP status, redaction, and what reaches a client.
- **Serialization** — the exact wire representation, including concepts, enums, temporal
  types, and maps.
- **Streaming and backpressure** — snapshot versus delta, revisions, heartbeats, what happens
  when a consumer is slow, and what bounds exist.
- **Cleanup** — disposal, connection and subscription teardown, and what survives a failure.
- **Generated metadata** — the descriptors, routes, schemas, and client shapes emitted for the
  behavior, from the templates and from the checked-in `TestApps` proxies.

## 3. Map responsibilities to the correct JVM modules

Module boundaries are a framework contract, stated in [`AGENTS.md`](../../../AGENTS.md):
`Source` is host-agnostic and must not depend on Spring Boot or Chronicle; `GradlePlugin` must
not depend on Spring Boot; web, security, WebSocket, and Chronicle seams stay optional. Decide
placement deliberately — do not let a port drag ASP.NET concepts into `Source`.

| If the .NET behavior is… | It belongs in |
| --- | --- |
| A host-independent pipeline, contract, or policy | `Source` |
| ASP.NET model binding, endpoint mapping, or middleware | `Integrations/SpringBoot` |
| An EF Core or MongoDB persistence seam | `Integrations/SpringDataJpa`, `Integrations/SpringDataMongo` |
| Chronicle-specific staging, concurrency, or read models | `Integrations/Chronicle` (optional) |
| A Roslyn analyzer or source generator | `CodeGeneration/KSP` |
| A proxy template or emitted client shape | `GradlePlugin` |
| Swagger or OpenAPI document generation | `Integrations/OpenApi` |
| Reusable in-process test support | `Testing` |
| Proof only, never product code | `ContractTests` and `Samples` |

A behavior that seems to need Spring inside `Source` almost always decomposes into a
host-neutral contract in `Source` plus a thin adapter in the integration. Split it that way.

## 4. Choose idiomatic JVM equivalents, and reuse the existing adapters

Do not mechanically port .NET types. Map the concept, then use the bridge this repository
already has — inventing a second bridge for the same concept is a defect, not a port.

| .NET concept | JVM equivalent already in this repository |
| --- | --- |
| Roslyn source generator / analyzer | KSP in `CodeGeneration/KSP`, with a stable `ARCKSP` diagnostic. See the **ksp-code-generation** skill |
| `Task`, `ValueTask`, `async`/`await` | Structured `suspend` on an application-owned scope; for Java, `JavaAsyncScope` and the `AsyncCommandPipeline`, `AsyncQueryPipeline`, `AsyncObservableQueryPipeline`, `AsyncAuthentication` facades in `Source/src/main/kotlin/io/cratis/arc/java/` |
| Returning `Task<T>` to a Java caller | `CompletionStage`, awaited without blocking through `io.cratis.arc.commands.CompletionStages` (`CompletionStageAwait.kt`) |
| `IAsyncEnumerable`, `IObservable`, Rx pipelines | Kotlin `Flow`, and `java.util.concurrent.Flow.Publisher` for Java; `asKotlinFlow()` in `Source/src/main/kotlin/io/cratis/arc/queries/JdkPublisherFlow.kt` adapts inward |
| Blocking or `CompletionStage` filter/validator/handler variants | The `Blocking*` and `Async*` adapters in `CommandAdapters.kt`, `QueryAdapters.kt`, `ValidationAdapters.kt`, `AuthorizationAdapters.kt` |
| ASP.NET filters, middleware, model binding | Generated performers plus `Integrations/SpringBoot`; there is no ASP.NET pipeline to mirror |
| `CommandContextManager` / ambient accessors | Explicit `CommandContext` and `QueryContext` parameters resolved by generated code. `ThreadLocal` for coroutine-visible state is forbidden |
| `System.Text.Json` options | Jackson via `io.cratis.arc.json.ArcObjectMapper` and `ArcJacksonModule` |
| FluentValidation, DataAnnotations | `CommandValidator`, `QueryValidator`, `ConceptValidator<TConcept>`, and Jakarta constraints translated by KSP |
| Handlebars `.hbs` proxy templates | `GradlePlugin/src/main/kotlin/io/cratis/arc/gradle/TypeScriptProxyGenerator.kt` |
| ASP.NET tenancy middleware | `Source/src/main/kotlin/io/cratis/arc/tenancy/` resolvers plus Spring request capture |

If genuinely nothing fits, say so explicitly in the PR and propose the new seam as a design
decision — do not smuggle it in as part of a port.

## 5. Prove it in both languages

Kotlin and Java are both first-class consumer languages, so a port is unfinished until it is
proven from both.

1. **Kotlin tests** in the owning module's `src/test/kotlin`, exercising the real pipeline
   rather than a mock of it.
2. **Real Java consumer tests** — compiled Java, not a Kotlin test asserting about Java. Put
   consumer contracts in `ContractTests/src/test/java/io/cratis/arc/contracts/` and runnable
   proof in `Samples/Java/`. Java compiles with `--release 17 -Xlint:all -Werror`, so an
   awkward generic or a missing `@JvmStatic` fails the build, which is the point.
3. **KSP positive and negative fixtures** whenever code generation is involved: a compile
   test in `CodeGeneration/KSP/src/test/kotlin/` and an invalid fixture under
   `ContractTests/src/negativeFixtures/`. Follow the **ksp-code-generation** skill.
4. **`.api` baseline updates** for every public contract move — `Source/api/Source.api`,
   `Integrations/*/api/*.api`, `Testing/api/Testing.api`, `CodeGeneration/KSP/api/KSP.api`,
   `GradlePlugin/api/GradlePlugin.api`. Land the `apiDump` in the same commit as the change.

Never add a stub, a no-op, or a fake implementation to make a gate pass. An unported branch of
behavior is a documented gap, not a silent placeholder.

## 6. Verify wire and proxy compatibility

If manifest metadata, generated proxies, routes, envelopes, or statuses can move, run the
deterministic and strict gates and read the generated diff. The full procedure is the
**typescript-proxy-contracts** skill; the gates are:

```shell
./gradlew :GradlePlugin:test --no-configuration-cache
./gradlew :GradlePlugin:verifyContractTestProxyDeterminism --no-configuration-cache
./gradlew :ContractTests:typeScriptBuild --no-configuration-cache
./gradlew :ContractTests:typeScriptRuntimeTest --no-configuration-cache
```

The runtime gate boots the real Kotlin Spring Boot sample and requires exact TAP totals with
zero fail, cancelled, skipped, or todo results. Update `Documentation/reference/http-contract.md`
when the servlet contract itself moved.

## 7. Document exactly what was proven

`Documentation/reference/parity.md` is the honest record, and its hedging is deliberate. Its
status key defines the vocabulary: **Implemented** requires source tests, contract tests,
integration tests, or runnable samples; **JVM-specific** marks an intentionally JVM-native
shape; **Partial** means usable behavior with a stated remaining boundary; **Not planned** is
outside the Spring Boot, model-bound direction.

- Change a row only for behavior an executable check now demonstrates.
- Keep the existing boundary sentences. If a boundary narrowed, rewrite it to the new, still
  accurate boundary — do not delete it and do not generalize a specific proof into a broad claim.
- Record intentional JVM divergence explicitly, as the "Roslyn analyzers", "Kotlin ergonomics",
  and "Java Core adapters" rows do, with the reason.
- Leave remaining gaps visible. A shorter matrix is not a better one.
- **Never upgrade a status on a source reading alone.** Reading the .NET implementation proves
  what .NET does; it proves nothing about this repository.

Then update the affected guide or reference page (`Documentation/guides/`,
`Documentation/reference/`) and run the documentation gate.

## Honesty boundaries

Every statement below is checked against `Documentation/reference/parity.md`. Restate them
accurately; never round them up.

- **The .NET proxy comparison is a normalized captured fixture, not raw output equivalence.**
  The ".NET-derived proxy differential gate" row records that `:GradlePlugin:test` compares
  against a repository-local expected fixture after CRLF-to-LF and generated-header
  normalization, capture-time namespace and query-name casing transformations, expected-side
  .NET import rewrites for `verbatimModuleSyntax`, and one expected-side correction at
  `Commands/CreateFixtures.ts`. It states plainly: "This remains a drift gate for the
  normalized fixture, not an exact raw .NET-output comparison; capture-time fixture
  preparation is not yet reproducible tooling." No normalization touches JVM output, and the
  fixture contains no `Guid`, `DateOnly`, or `TimeOnly`.
- **Overall Arc .NET parity is Partial.** The "Full Arc .NET parity" row is `Partial`: the
  normalized fixture "covers selected generated shapes, but neither that fixture nor the
  project claims exact raw-output compatibility or every Arc .NET feature, analyzer,
  persistence seam, or hosting model."
- **Cross-store transactions are not distributed atomic transactions.** The "Cross-store
  transactions" row is `Not planned`: Chronicle, JPA, and MongoDB scopes "cannot form one
  distributed atomic transaction". The "Chronicle event transactions" row adds "No cross-store
  distributed transaction is provided", and the paragraph closing the "Ordered P0 parity
  backlog" section states MongoDB may commit before JPA fails and both local stores may commit
  before Chronicle fails or has an indeterminate external outcome.
- **Temporal mappings have specific documented boundaries.** Per "Temporal and UUID proxies":
  `Duration` is ISO-8601 text, TypeScript `string`, OpenAPI `string`/`duration` — deliberately
  not Fundamentals `TimeSpan`, because Java and C# wire formats differ; tested `Period`
  behavior is limited to Core ISO-8601 round-trip and TypeScript `string`; `OffsetTime`
  generates as textual, untyped `string`; and `LocalDateTime`, `Instant`, `OffsetDateTime`, and
  `ZonedDateTime` remain JavaScript `Date`. The "Remaining temporal client limits" section adds
  that raw `08:09:10.1235567` hydrates as `08:09:10.123`, proving truncation rather than
  rounding; that Arc accepts and emits `LocalTime` with up to seven fractional digits and
  rejects finer values rather than rounding them; and that explicit RFC QUERY bodies pass
  `DateOnly`/`TimeOnly` component objects to native `JSON.stringify` because those classes have
  no `toJSON()`, so GET is preferred until upstream serialization changes.
- **Kotlin omitted defaults are a distinct state from an explicit value.** "One-shot queries"
  records that omitted Kotlin client defaults execute at invocation while supplied values,
  including explicit null, retain presence. "Command and query-model validation" records that
  an omitted default has no pre-invocation value, so executable violations for that slot are
  ignored and the default executes at the model boundary. "Introspection", "TypeScript one-shot
  proxies", "TypeScript observable proxies", and "OpenAPI" all record that defaulted client
  parameters are optional and non-required and expose **no** invented value, expression, or
  copied server literal.

Two smaller boundaries worth carrying into a port: KSP cannot discover
`@HandlesCommandResponseValues` that exists only in a dependency binary, and erased
`CommandResponseValues` contents remain untyped metadata ("Aggregate command responses");
credit-card constraints stay server-only because the pinned client runtime has no matching rule
("Validation metadata").

## Verify

```shell
./gradlew clean build --no-configuration-cache -x :ContractTests:typeScriptRuntimeTest
./gradlew apiCheck
./gradlew :GradlePlugin:verifyContractTestProxyDeterminism :ContractTests:typeScriptBuild --no-configuration-cache
./gradlew :ContractTests:typeScriptRuntimeTest --no-configuration-cache
./Documentation/verify-markdown.sh
```

Done means all of the following, and say so with evidence:

- The pinned Arc .NET sha and `describe --tags` output are recorded in the PR.
- The written behavior description exists and names anything left unspecified in .NET.
- Every affected module builds with zero warnings and zero errors.
- The behavior is exercised from Kotlin **and** from compiled Java.
- KSP fixtures exist in both directions where code generation moved.
- `.api` baselines are current, with the `apiDump` in the same commit.
- Proxy generation is deterministic, compiles strictly, and the runtime gate reports exact
  TAP totals.
- `Documentation/reference/parity.md` changed only where an executable check demonstrates the
  new behavior, and every remaining gap is still stated.
- Nothing in the Arc .NET checkout was modified.
