---
applyTo: "**/*"
---

## The rules

### 1. Never upgrade a status without naming demonstrated evidence

`Documentation/reference/parity.md` defines its own bar in the status key:

> | Implemented | Available and covered by source tests, contract tests, integration tests, or
> runnable samples. |

A status change from `Partial` to `Implemented` (or the addition of an `Implemented` row) is only
valid when you can name the artifact that proves it, in the change itself:

- a **test** — module path and test class, for example
  `Integrations/SpringBoot/src/test/kotlin/io/cratis/arc/springboot/ArcQueryHostingTests.kt`;
- a **contract test** — a fixture or gate under `ContractTests`, for example
  `:ContractTests:typeScriptBuild` or `:ContractTests:typeScriptRuntimeTest`;
- a **runnable sample** — for example `Samples/Kotlin/SpringBoot` or `Samples/Java/SpringBoot`,
  including which task or request exercises it.

"It compiles", "`apiCheck` passes", and "the code looks correct" are not evidence of behavior. If you
cannot name the artifact, the row stays where it is and you say what is missing.

### 2. Verified by an executable check is not the same as read and compared

Keep these three claims distinct and never let the weaker one be written as the stronger:

| Claim | What you may write |
| --- | --- |
| An executable check asserts the behavior | "Implemented", and name the check |
| Behavior observed once by hand, no check | Describe it as observed, keep the status unchanged, and add the check before claiming it |
| The .NET source was read and looks equivalent | Say exactly that, in prose, with the .NET path — this is never a parity status |

Reading `Source/DotNET/...` and concluding "the JVM does the same thing" is a hypothesis. It becomes
a parity claim only after a JVM-side check fails when the behavior is broken.

### 3. The proxy differential is a normalized-fixture drift gate, not raw output equivalence

This is the caveat most likely to be overstated. `parity.md` says, verbatim:

> This remains a drift gate for the normalized fixture, not an exact raw .NET-output comparison;
> capture-time fixture preparation is not yet reproducible tooling.

Never describe `:GradlePlugin:test`'s `.NET`-derived comparison as "byte-identical to .NET output",
"proves proxy parity", or "the generated TypeScript matches Arc .NET". What it actually compares is
the sorted output path set and bodies of JVM output against a **repository-local expected fixture**,
after CRLF-to-LF and generated-header normalization, the fixture's capture-time namespace and
query-name casing transformations, and expected-side .NET import rewrites required by
`verbatimModuleSyntax`. It also carries one deliberate expected-side correction at
`Commands/CreateFixtures.ts` (`Command<ICreateFixtures, FixtureModel>` to
`Command<ICreateFixtures, FixtureModel[]>`), because the .NET side already calls
`super(FixtureModel, true)`. `parity.md` further limits what the map fixture proves:

> It proves that one string-key/string-value Record fixture, not non-string keys, nullable entries,
> typed model values, `ValueMap`, or broader dictionary parity.

Two invariants follow. **No normalization may ever transform JVM output** — normalizations exist only
on the expected side, so a JVM regression still fails the gate. And the fixture contains no `Guid`,
`DateOnly`, or `TimeOnly`, so the temporal/UUID mapping is covered by focused generator and contract
tests instead; do not attribute that coverage to the differential.

### 4. Overall parity is Partial, and specific boundaries are documented

State the overall position exactly as `parity.md` does:

> | Full Arc .NET parity | Partial | The semantically normalized .NET-derived proxy fixture covers
> selected generated shapes, but neither that fixture nor the project claims exact raw-output
> compatibility or every Arc .NET feature, analyzer, persistence seam, or hosting model. |

The following boundaries are documented and must not be smoothed over. Each was re-verified against
`Documentation/reference/parity.md` before being restated here:

- **Temporal mappings are lossy at the client.** `LocalDate`, `LocalTime`, and `UUID` map to
  `DateOnly`, `TimeOnly`, and `Guid` from `@cratis/fundamentals`, but "`LocalDateTime`, `Instant`,
  `OffsetDateTime`, and `ZonedDateTime` remain JavaScript `Date`", and mapping `LocalDateTime`
  "invents a zone" while `OffsetDateTime`/`ZonedDateTime` lose "original offset or zone identity".
  `OffsetTime` generates as untyped `string`. `Duration` is ISO-8601 text and is deliberately **not**
  Fundamentals `TimeSpan` "because Java and C# wire formats differ"; the tested `Period` contract is
  limited to Core round-trip plus TypeScript `string`. The `TimeOnly` behavior is truncation, not
  rounding: raw `08:09:10.1235567` hydrates as `08:09:10.123`, and `parity.md` explains why —
  "because rounding would yield `.124`". Arc accepts up to seven fractional digits for 100 ns
  compatibility, rejects eight or nine on deserialization, and rejects finer-than-100 ns on
  serialization rather than rounding or truncating.
- **Kotlin omitted-default handling is a JVM-specific contract with no C# counterpart.** "Omitted
  Kotlin client defaults execute at invocation, while supplied values, including explicit null,
  retain presence." Metadata records only *that* a default exists: introspection reports `hasDefault`
  and removes the parameter from `required`, OpenAPI marks it non-required with "no invented OpenAPI
  default", and generated TypeScript makes it optional "without copying a server literal". For
  validation, "an omitted Kotlin default has no pre-invocation value, so executable violations for
  that slot are ignored and the default executes at the model boundary". Shapes KSP cannot support
  fail with `ARCKSP0209` rather than falling back to reflection. Never describe any of this as
  matching .NET behavior.
- **Cross-store work is not a distributed transaction.** `parity.md` lists cross-store transactions
  as *Not planned*: "Chronicle, JPA, and MongoDB scopes cannot form one distributed atomic
  transaction; applications must design for partial completion and compensation." Chronicle staging
  is a commit barrier only — "No cross-store distributed transaction is provided" — and the P0
  backlog note is explicit that "MongoDB may commit before JPA fails, and both local stores may
  commit before Chronicle fails or has an indeterminate external outcome." Imperative JPA and
  MongoDB command transactions remain thread-bound, fixed-store opt-ins that are off by default.
  Never call any of this atomic, transactional across stores, or a distributed transaction.

### 5. Record intentional JVM divergence explicitly

When the JVM shape deliberately differs, say so in the row rather than letting the difference pass
silently. `parity.md` already uses a dedicated status for this:

> | JVM-specific | Supported with an intentionally JVM-native shape. |

Existing examples to follow: `suspend` and `CompletionStage` handlers, Kotlin `Flow` and JDK
`Flow.Publisher` observables, the Spring Data integrations, Kotlin ergonomics, the Java Core
adapters, and "Roslyn analyzers | JVM-specific | KSP compile-time diagnostics are the JVM substitute;
Roslyn does not apply." A divergence you introduce and do not record is a defect, because the next
reader will assume equivalence. Say which behavior differs, and why the JVM shape was chosen.

### 6. Quote the document; do not paraphrase it loosely

`Documentation/reference/parity.md` is worded carefully — "selected generated shapes", "remains a
drift gate", "no cross-store distributed transaction is provided", "not an exact precision
round-trip". When you restate a boundary anywhere else, quote the sentence or link to the row rather
than compressing it. A summary that drops a qualifier is how a hedge silently becomes a promise.
Loosening any existing wording requires the same evidence as a status upgrade.
