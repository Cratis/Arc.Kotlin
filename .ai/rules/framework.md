# Framework Design

This file governs how Arc.Kotlin's *published* surface is designed and evolved: the host-agnostic
contracts in `Source`, the compile-time artifacts produced by `CodeGeneration/KSP`, the starters
under `Integrations/`, the `io.cratis.arc` Gradle plugin, and the reusable scenarios in `Testing`.
Arc.Kotlin is a framework other people's builds depend on, so a design decision here is a promise,
not a preference. Style rules for the languages themselves live in [kotlin.md](./kotlin.md) and
[java.md](./java.md); dual-language API shape lives in
[kotlin-java-interop.md](./kotlin-java-interop.md).

## This repository builds a framework, not an application

- **There is no product domain here.** `CreateTask`, `TaskView`, and `FixtureModel` exist to prove a
  contract, not to model a business. Never introduce domain concepts, application layering, vertical
  slices, or feature folders. Add a fixture in `ContractTests` or a sample under `Samples/` instead.
- **Every `public` declaration is someone else's compile dependency.** `Source`,
  `CodeGeneration/KSP`, `GradlePlugin`, `Testing`, and all six integrations carry checked-in `.api`
  baselines precisely so that no public shape moves by accident. If `apiCheck` reports a diff you
  did not intend, the code is wrong — not the baseline.
- **Samples consume the published starters.** `Samples/Kotlin/SpringBoot` and
  `Samples/Java/SpringBoot` depend on `project(":Integrations:SpringBoot")` and are wired with a
  plain `@SpringBootApplication`; they must keep using public APIs only. A sample that needs an
  internal to work is evidence the public API is incomplete.
- **The consumer is a Kotlin *or* Java Spring Boot application.** A design that only reads well from
  Kotlin is unfinished. `ConceptAs<T>` is the model: its abstract member is `fun value(): T` so a
  Java `record OrderId(UUID value) implements ConceptAs<UUID>` satisfies it with no boilerplate, and
  Kotlin gets the `value` property view through a `@JvmSynthetic` extension.

## Lovable-API design rules

These are the repository's product requirements, not aspirations. Apply them when adding or changing
anything public.

### Sane defaults with a documented override point

Ship a working default and make replacing it exactly one declaration. In the Spring host, every
default is registered with `@ConditionalOnMissingBean`, so an application bean silently wins:
`ArcAutoConfiguration.arcTenantIdResolver`, `arcCommandPipeline`, `arcQueryRenderers`, and
`arcQueryHealthTracker` all follow that shape. When you add a default, add the corresponding
`@ConditionalOnMissingBean` in the same commit and say in `Documentation/` how to override it.

### Convention over configuration

Derive what can be derived. Routes come from `EndpointRouteHelper` plus `ApiEndpointOptions` and the
`cratis.arc.endpoints` properties; `@Path` is the explicit opt-out for a query that needs a literal
route, and an explicit path is preserved verbatim. Do not add a configuration knob for something the
model already states.

### Discovery by annotation and naming

Arc finds artifacts; consumers do not register them. The established patterns are:

| Artifact | Discovery contract |
| --- | --- |
| Command | `@Command` on a class or record plus one public instance `handle`; optional `provide` |
| Query | `@ReadModel` on the result model plus static Java or `@JvmStatic` companion methods |
| Service parameter | `@FromServices` on a query parameter; command handler parameters resolve by signature |
| Generated module | `ArcArtifactModule` subclasses found through `ServiceLoader` *and* Spring beans, deduplicated and ordered by class name in `ArcArtifactModules` |

A new artifact kind must follow the same shape: an annotation on the model, a generated
reflection-free invoker, and registration through `ArcArtifactModule`. Do not invent a parallel
registration mechanism.

### Compile-time over reflection

Work belongs in KSP whenever it can be decided from source. `CommandHandler` and `QueryPerformer`
are described as *build-time generated, reflection-free invokers*, and the runtime honors that. When
a shape is not supportable, fail the compilation with a stable code from
`CodeGeneration/KSP/DIAGNOSTICS.md` (for example `ARCKSP0109` for ambiguous command response values,
`ARCKSP0209` for an unsupported Kotlin query parameter default) rather than throwing at request
time. Adding a new failure mode means adding a new `ARCKSP` code and documenting it — see
[ksp.md](./ksp.md).

### Specialization over over-generalized reuse

Prefer several focused contracts to one type stretched across conflicting scenarios. Validation is
the worked example: `CommandValidator<T>`, `QueryValidator`, and `ConceptValidator<TConcept>` are
three separate interfaces because they are matched, ordered, and applied differently — they were not
collapsed into a single `Validator`. The same applies to `QueryRendererFor<T>` versus
`InterceptReadModel<T>` versus `GuardObservableQueryEmission`.

### Consistency with the surrounding area

Before adding to a package, read what is already there and match it: naming (`Default*` for the
built-in implementation, `Concurrent*` for a thread-safe registry, `Arc*` for a Spring-facing type),
ordering semantics, immutability (`java.util.List.copyOf` on constructor parameters), and KDoc that
states the contract rather than restating the signature. A locally clever deviation costs more than
it saves.

### Fail closed, and say what the caller should do

Framework code never guesses. Without a usable `@CommandKey`, Arc returns a validation result with
`reason: "rule"` and `reasonDetail: "commandKey"` instead of inventing an event-source identifier.
Overload, oversized bodies, and exhausted admission return 413/429/503 rather than growing memory.
Match that posture: reject with an actionable message, do not degrade silently.

## Evolving a published surface

- **Additive first.** Add new members with a default implementation so existing implementors keep
  compiling — `CommandHandler.prepare` carries a default explicitly to preserve source compatibility
  for manual and previously generated handlers. `Source` compiles with
  `-Xjvm-default=all-compatibility` so interface defaults are usable from Java.
- **Add overloads instead of changing signatures.** Use `@JvmOverloads` on constructors with
  defaults (`ArcArtifactManifest`, `ArcPrincipal`, `QueryRequest` all do) so Java callers keep their
  existing call sites.
- **A deliberate breaking change lands as an `apiDump` in the same commit**, is called out in
  `Documentation/`, and is stated as breaking. The generated-TypeScript temporal/UUID change is the
  precedent: `Documentation/reference/parity.md` says plainly that it "is source-breaking for
  consumers that assigned `Date` or scalar strings".
- **Versioned wire contracts move deliberately.** Bumping
  `ArcArtifactManifest.CURRENT_FORMAT_VERSION` is a decision with a documented migration, not a side
  effect.
- **Deprecate before removing**, and keep the compatibility projection working while the deprecation
  stands (legacy `ParameterDescriptor` constructors still project to `CLIENT` or `SERVICE`).

## No placeholder behavior — a hard prohibition

This is the same rule as `AGENTS.md`, stated so it cannot be missed. **Do not add placeholder
behavior, fake implementations, or no-op stubs to make a gate pass.**

Concretely, all of the following are forbidden:

- An override that returns `null`, `emptyList()`, `true`, or a hard-coded value so a test or
  `apiCheck` goes green, when the real behavior is not implemented.
- A `TODO`/`FIXME` left in place of behavior a public API claims to provide.
- Catching an exception to keep a pipeline "working" without an actual outcome for the caller.
- A generated artifact, manifest entry, proxy, or OpenAPI schema fabricated to satisfy a comparison
  rather than produced by the real generator.
- A parity status, documentation sentence, or KDoc claim describing behavior that does not run.

If a slice cannot be finished, leave it unimplemented and say so. An honest gap plus an accurate
`Documentation/reference/parity.md` entry is correct; a stub that makes the build green is a defect
that will be trusted later. See [arc-parity.md](./arc-parity.md) for how status claims must be
evidenced.

## Deciding where code belongs

`Source` is host-agnostic and must not depend on Spring Boot or Chronicle — verified: there is no
`org.springframework` or `io.cratis.chronicle` reference anywhere in
`Source/src/main/kotlin`. Use this decision order:

1. **Can the behavior be expressed with only JDK, Kotlin coroutines, Jackson, SLF4J, and Jakarta
   Validation API types?** Then it belongs in `Source`, in the package that owns the concern
   (`commands`, `queries`, `validation`, `authorization`, `authentication`, `identity`, `tenancy`,
   `introspection`, `artifacts`, `metadata`, `polymorphism`, `json`, `http`, `results`, `concepts`).
2. **Does it need a servlet, a Spring bean, or a Spring type?** It belongs in
   `Integrations/SpringBoot`. Keep the host-neutral half in `Source` as an interface and the Spring
   half as an autoconfiguration bean — `TenantIdResolver` lives in `Source`, while
   `ArcTenantResolutionService` and the property binding live in the integration.
3. **Does it need a specific store, protocol, or product?** It belongs in its own integration
   module: `SpringDataJpa`, `SpringDataMongo`, `OpenApi`, `Observability`, or `Chronicle`. These may
   depend on `Source` and on `Integrations:SpringBoot`; `Source` must never depend back.
4. **Is it decidable from source at compile time?** Then the *generation* belongs in
   `CodeGeneration/KSP` and only the *runtime contract it targets* belongs in `Source`.
5. **Is it build wiring?** It belongs in `GradlePlugin`, which must not depend on Spring Boot.

When a contract is only meaningful with a host present, still define the interface in `Source` so
the pipeline stays testable without a host — that is why `CommandExecutionScope`,
`CommandResponseValueHandler`, and `ServiceResolver` are host-neutral while their real
implementations are supplied by integrations.

## Checklist for a new extension point

1. Define the host-neutral contract in the owning `Source` package, with KDoc that states the
   ordering, nullability, and failure semantics.
2. Provide a Java-friendly shape (`fun interface`, method-form members, `@JvmOverloads`) and a
   Kotlin property view via `@JvmSynthetic` extension where the Kotlin shape would otherwise be
   awkward. Add a blocking and/or `CompletionStage` adapter in `io.cratis.arc.java` if implementing
   it from Java would otherwise require coroutines.
3. Wire the default in the relevant autoconfiguration behind `@ConditionalOnMissingBean`, collecting
   application beans with `ObjectProvider<T>.orderedStream()` so declaration order is the complete
   precedence rule.
4. Add tests in the owning module, and a `ContractTests` fixture when the shape is visible to
   generated artifacts or consumers.
5. Update `.api` baselines with an intentional `apiDump`.
6. Update `Documentation/` — the relevant guide plus `Documentation/reference/` — and run
   `./Documentation/verify-markdown.sh`.
