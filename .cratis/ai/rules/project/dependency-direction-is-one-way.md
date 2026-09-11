---
applyTo: "**/*"
---

## Dependency direction is one-way

- **`Source` must never depend on Spring.** Verified: there is no `org.springframework` reference
  anywhere under `Source/src/main/kotlin`. Host-neutral contracts (`CommandPipeline`,
  `QueryPipeline`, `CommandExecutionScope`, `ServiceResolver`, `TenantIdResolver`,
  `AuthenticationHandler`, `IdentityDetailsProvider`) live in `Source`; the Spring adaptation of each
  lives here.
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
