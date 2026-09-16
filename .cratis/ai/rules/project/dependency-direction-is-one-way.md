---
applyTo: "**/*"
---

## Dependency direction is one-way

- **Arc targets Spring Boot, not a separate host-independent Core product.** Keep the module
  layout and Maven coordinates unchanged. `Source` (`io.cratis:arc`) may assume Spring Boot
  semantics, but **all compiled `io.cratis.arc.artifacts`, `metadata`, and `json` classes,
  actual KSP/Gradle-consumed types, and their transitive local class references must remain
  Spring-free**. This includes generic signatures, annotations, and implementation linkage,
  not just imports or public declarations. `./gradlew checkSpringBoundary` uses JDK `jdeps`
  plus constant-pool descriptor inspection on production class outputs. It resolves reachable
  external superclass/interface/class-signature closure from production dependencies, failing
  closed on missing or unreadable definitions. It rejects Spring coordinates and class definitions
  on both tool compile and runtime classpaths, including file dependencies. Compiled separate-JAR
  mutations and isolated Gradle dependency mutations guard these checks. It is wired into
  `:Source:check`, `:CodeGeneration:KSP:check`, and `:GradlePlugin:check`. Symbolic Spring type
  names used by KSP for consumer inspection are not linkage; descriptor-shaped constants are
  conservatively checked, including Kotlin metadata. JDK classes are terminal after checking
  their presence in the selected JDK's module inventory. Dynamic reflection, resources, and
  external method bodies and unused member signatures are outside the static class check:
  optional external APIs are not recursively expanded. This does not prove arbitrary external
  implementation safety. No Spring dependency is
  added by this positioning decision. Spring adaptation stays in the starter, and optional
  integrations must not leak into compiler/Gradle classpaths.
- The starter's `build.gradle.kts` declares three `api` dependencies — `project(":Source")`,
  `spring-boot`, and `spring-boot-autoconfigure`. Jackson 3 is exposed transitively by `Source`.
  Everything else is deliberately `compileOnly`: `spring-boot-starter-webmvc`,
  `spring-boot-starter-websocket`, `spring-boot-starter-security`,
  `jakarta.validation:jakarta.validation-api`, and `spring-boot-configuration-processor`.
- Do not promote a `compileOnly` dependency to `api`/`implementation` to make something compile. If
  a feature needs a library at runtime, it belongs behind a `@ConditionalOnClass` guard with the
  library still `compileOnly`, or in a separate integration module.
- `Integrations/{SpringDataJpa,SpringDataMongo,OpenApi,Observability,Chronicle}` may depend on
  `Source` and on this starter; nothing depends back on them.
