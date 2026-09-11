---
applyTo: "**/*"
---

## Spring Boot 4.1.x is the only supported host baseline

Spring Boot 4 and Arc share one Jackson 3 mapper. Arc contributes `ArcJacksonModule` plus a native
`JsonMapperBuilderCustomizer` named `arcJacksonCustomizer`, so generated Arc endpoints and
conventional MVC controllers use the same naming, inclusion, temporal, enum, concept, and derived-type
wire policy. Application-supplied mappers remain authoritative because Jackson 3 mappers are
immutable; create an Arc-configured replacement with `ArcObjectMapper.configure(mapper)` when needed.

Hold unverified Spring Boot minors at 4.1.x until their managed Jackson and coroutines versions are
synchronized deliberately.

`AGENTS.md`, `README.md`, and `Documentation/reference/parity.md` all state that Spring Boot is the
only host, and the parity
matrix lists **Non-Spring hosting** as *Not planned*: "Spring Boot is the only supported host
integration; Core remains host-independent." Do not add Ktor, Micronaut, Quarkus, a raw servlet
container, or a Spring WebFlux host, and do not add abstractions whose only purpose is to make a
second host possible. `Controllers` are likewise *Not planned* — Arc generates model-bound Spring MVC
endpoints, and a hand-written controller is not the extension mechanism. If a request seems to
require another host, stop and raise it rather than starting one.

---

# Generated TypeScript Proxies

This rule governs the generated TypeScript proxy surface this repository owns. **Arc.Kotlin has no
TypeScript UI** — there is no React application, no component library, no frontend build to maintain.
It does own a **generated TypeScript proxy contract**: the Gradle plugin renders `.ts` clients from
Arc artifact manifests, and three gates prove that output is deterministic, strictly compilable, and
correct against a real running JVM host. Saying "this repository has no TypeScript" is wrong and
leads to skipped gates.
