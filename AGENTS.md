# Arc.Kotlin — Project Instructions

## Scope

Arc.Kotlin is a framework/library repository for Kotlin and Java applications on Spring Boot. It is not an event-sourced application repository: do not apply C# conventions, application vertical-slice layouts, or Chronicle event-modeling rules to this codebase.

## Platform and compatibility

- Target JDK 17 and Gradle 8.13.
- Kotlin is the implementation language; Java is a first-class consumer language. Keep public APIs straightforward from Java, avoid Kotlin-only call patterns at public boundaries, and verify important APIs from both languages.
- Spring Boot is the only supported host integration. Do not introduce Ktor or another host framework.
- Use Jackson for JSON. Do not introduce Gson.
- Prefer structured coroutine context and explicit request/context propagation. Never use `ThreadLocal` for coroutine-visible state.
- Treat compiler warnings as errors and use JUnit 5.
- Add binary compatibility baselines for published runtime APIs and update them deliberately when public contracts change.

## Architecture

- `Source` is host-agnostic and must not depend on Spring Boot or Chronicle.
- `CodeGeneration/KSP` contains KSP compile-time tooling and may depend on `Source`.
- `GradlePlugin` contains the `io.cratis.arc` Gradle plugin and must not depend on Spring Boot.
- `Integrations/SpringBoot` depends on `Source`; web and security integration must remain optional.
- `Integrations/Chronicle` is optional and depends on `Source`, the Spring Boot integration, and the released Chronicle JVM client.
- `Testing` contains reusable test support and depends on `Source`.
- `ContractTests` is unpublished and verifies Kotlin and Java consumer contracts.
- Samples consume the public starters; they must not bypass public APIs with project internals.
- Do not add placeholder behavior, fake implementations, or no-op stubs merely to make a build pass.

## Code and documentation

- Start source and build files with the standard Cratis MIT header.
- Use American English in code, comments, and documentation.
- Document actual behavior and status only; do not claim parity with another Arc implementation until it exists and is verified.

## Local AI work artifacts

Create plans, handovers, session notes, scratch analyses, and other AI work records only under `.ai-work/` at the repository root. The directory is gitignored and must never be committed. `.pi/tasks/` and `.pi/delegate/` are local execution artifacts and must also remain untracked.
