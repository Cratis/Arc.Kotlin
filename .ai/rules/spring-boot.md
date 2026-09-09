# Spring Boot Integration

This file governs `Integrations/SpringBoot` (published as `io.cratis:arc-spring-boot-starter`) and
the Spring-facing surface of the other integrations: how autoconfiguration is structured, how
optional dependencies are expressed, how configuration properties are named and documented, and how
Spring bean lifetime interacts with Arc's coroutine model. Spring Boot 4.1.x is the **only** supported
host integration baseline — see the boundary rule at the end of this file. Arc retains its published
Jackson 2 (`com.fasterxml.jackson`) contract through Boot's `spring-boot-jackson2` compatibility
module while Boot's application stack defaults to Jackson 3 (`tools.jackson`). That bridge is bounded;
issue #150 owns the public Jackson 3 migration required before Spring Boot removes it. Framework-wide design principles
live in [framework.md](./framework.md); build wiring lives in [gradle.md](./gradle.md).

## Dependency direction is one-way

- **`Source` must never depend on Spring.** Verified: there is no `org.springframework` reference
  anywhere under `Source/src/main/kotlin`. Host-neutral contracts (`CommandPipeline`,
  `QueryPipeline`, `CommandExecutionScope`, `ServiceResolver`, `TenantIdResolver`,
  `AuthenticationHandler`, `IdentityDetailsProvider`) live in `Source`; the Spring adaptation of each
  lives here.
- The starter's `build.gradle.kts` declares four `api` dependencies — `project(":Source")`,
  `spring-boot`, `spring-boot-autoconfigure`, and the bounded `spring-boot-jackson2` compatibility
  bridge. Everything else is deliberately `compileOnly`: `spring-boot-starter-webmvc`,
  `spring-boot-starter-websocket`, `spring-boot-starter-security`,
  `jakarta.validation:jakarta.validation-api`, and `spring-boot-configuration-processor`.
- Do not promote a `compileOnly` dependency to `api`/`implementation` to make something compile. If
  a feature needs a library at runtime, it belongs behind a `@ConditionalOnClass` guard with the
  library still `compileOnly`, or in a separate integration module.
- `Integrations/{SpringDataJpa,SpringDataMongo,OpenApi,Observability,Chronicle}` may depend on
  `Source` and on this starter; nothing depends back on them.

## Registered autoconfigurations

Exactly five classes are listed in
`Integrations/SpringBoot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
Know which layer you are editing:

| Class | Guarded by | Owns |
| --- | --- | --- |
| `ArcAutoConfiguration` | none (host-neutral) | registries, pipelines, authentication, authorization, tenancy resolution, introspection, artifact modules, coroutine scope, Jackson 2 wiring |
| `ArcCorrelationAutoConfiguration` | servlet application with `cratis.arc.correlation-enabled` enabled (the default) | host-wide correlation request wrapping, response headers, and servlet-thread MDC |
| `ArcValidationAutoConfiguration` | `@AutoConfiguration(after = [ArcAutoConfiguration::class])`, `@ConditionalOnClass(name = ["jakarta.validation.Validator"])` | the Jakarta Bean Validation command and query filters |
| `ArcWebAutoConfiguration` | `@ConditionalOnWebApplication(type = SERVLET)`, `@ConditionalOnClass(name = ["jakarta.servlet.Servlet", "org.springframework.web.servlet.DispatcherServlet"])` | servlet hosting: the authentication filter registration, observable-query transport, and the command/query handler mapping |
| `ArcObservableQueryWebSocketConfiguration` | `@ConditionalOnClass(name = ["org.springframework.web.socket.config.annotation.WebSocketConfigurer"])` plus `@ConditionalOnProperty(prefix = "cratis.arc.observable-queries", name = ["web-socket-enabled"], havingValue = "true", matchIfMissing = true)` | direct and multiplexed WebSocket routes |

`ArcAutoConfiguration` must keep working in a non-web application. Anything that touches
`HttpServletRequest`, `HttpRequestHandler`, `FilterRegistrationBean`, or a handler mapping belongs in
`ArcWebAutoConfiguration`, not in `ArcAutoConfiguration`.

Each of the other integrations registers exactly one autoconfiguration in its own `.imports` file:

| Module | Autoconfiguration | Boundary in one sentence |
| --- | --- | --- |
| `Integrations/SpringDataJpa` | `ArcSpringDataJpaAutoConfiguration` | Adapts Spring Data JPA to Arc — `QueryRequest`/`Pageable`/`Sort` and `Page<T>` conversion, tenant-certified `JpaPersistenceUnit` routing, command-side read-model resolution, an explicit change notifier for observable `Flow` snapshots, and an off-by-default imperative command transaction scope. |
| `Integrations/SpringDataMongo` | `ArcSpringDataMongoAutoConfiguration` | The same adaptation for Spring Data MongoDB, with tenant-certified `TenantMongoOperations` and change-stream-backed observable `Flow` snapshots. |
| `Integrations/OpenApi` | `ArcOpenApiAutoConfiguration` | Generates and caches an OpenAPI 3.1 document from Arc artifact metadata and serves it at `/v3/api-docs` and `/.cratis/openapi.json`, backing off its document bean when the application supplies its own `OpenAPI` or `ArcOpenApiDocument`. |
| `Integrations/Observability` | `ArcObservabilityAutoConfiguration` | Decorates Arc command, query, authentication, and identity execution with Micrometer observations plus optional OpenTelemetry baggage and SLF4J MDC correlation. |
| `Integrations/Chronicle` | `ChronicleArcAutoConfiguration` | Adds tenant-aware Chronicle event-store resolution, a staged command transaction, event response-value handlers, read-model resolution/release, and a reactor command-side-effect handler. |

## Optionality is expressed with conditions, never with a hard dependency

- **Web and security must remain optional.** `ArcWebAutoConfiguration` guards on servlet classes by
  *name*, and Spring Security is handled by two mutually exclusive nested configurations:
  `ArcWebAutoConfiguration.Security` (`@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])`)
  supplies `SpringSecurityArcPrincipalFactory`, while `ArcWebAutoConfiguration.ServletIdentity`
  (`@ConditionalOnMissingClass("org.springframework.security.core.Authentication")`) supplies
  `ServletArcPrincipalFactory`. Never make Spring Security a required dependency, and never let one
  of those two paths become the only path.
- **Use the string form of `@ConditionalOnClass`** when the guarded type is not on this module's
  compile classpath in every configuration, as `ArcWebAutoConfiguration` and
  `ArcValidationAutoConfiguration` do. Class-literal conditions are appropriate only where the
  module already declares the dependency (for example
  `@ConditionalOnClass(EntityManagerFactory::class, PlatformTransactionManager::class)` in the JPA
  integration).
- **Optional-by-property behavior uses `@ConditionalOnProperty` with an explicit default.** WebSocket
  hosting is `matchIfMissing = true` (on unless disabled); the imperative JPA and MongoDB command
  transaction scopes are opt-in and stay off unless
  `cratis.arc.spring-data.{jpa,mongodb}.command-transactions-enabled=true`.
- **Every default bean backs off for an application bean.** Use `@ConditionalOnMissingBean(Type::class)`
  when the contract type is Arc's own (`TenantIdResolver`, `QueryRenderers`, `ReadModelInterceptors`,
  `QueryHealthTracker`, `ObservableQueryEmissionGuards`). Use the **named** form —
  `@Bean("arcJakartaBeanValidationCommandFilter")` with
  `@ConditionalOnMissingBean(name = ["arcJakartaBeanValidationCommandFilter"])` — when the declared
  return type is a type applications legitimately register many of (`CommandFilter`, `QueryFilter`,
  `BeanPostProcessor`, `FilterRegistrationBean`, `SimpleUrlHandlerMapping`). The Jackson 2 defaults
  bean retains the historical `arcJacksonCustomizer` name even though Boot 4 requires a
  warning-free BeanPostProcessor instead of its deprecated compatibility customizer interface.
  Getting this wrong silently disables Arc's own bean; a bean-backoff test is required.
- **Collect application contributions with `ObjectProvider<T>.orderedStream()`**, so Spring `@Order`
  is the complete and documented precedence rule, as `arcCommandPipeline`, `arcQueryRenderers`, and
  `arcAuthentication` all do.

## Configuration properties

- The prefix is `cratis.arc` and nothing else. `ArcProperties` is `@ConfigurationProperties("cratis.arc")`
  with nested `EndpointProperties`, `ObservableQueryProperties`, and `ArcTenancyProperties`; the
  observability starter adds `cratis.arc.observability` through `ArcObservabilityProperties`.
- **Property classes are Java, with getters and setters** (`ArcProperties.java`,
  `ArcTenancyProperties.java`, `ArcObservabilityProperties.java`). Keep them that way: relaxed
  binding, the configuration processor, and Java consumers all depend on the JavaBean shape.
- **Property names are kebab-case in configuration and camelCase in code**
  (`coroutine-parallelism` ↔ `coroutineParallelism`). Enable a new autoconfiguration through a
  property only when a class-presence condition cannot express the same thing.
- **Validate in the setter and say what is wrong.** The established pattern throws
  `IllegalArgumentException` with an actionable message —
  `"coroutineParallelism must be greater than zero."` — so the application fails at startup, not at
  the first request.
- **A new property requires three edits in the same commit:**
  1. the field, getter, and validating setter on the properties class;
  2. an entry in `src/main/resources/META-INF/additional-spring-configuration-metadata.json` with
     `type`, `defaultValue`, and a `description` (only `Integrations/SpringBoot` and
     `Integrations/Observability` currently ship this file);
  3. a row in `Documentation/reference/configuration.md` with the exact default.
- Properties consumed only by `@ConditionalOnProperty` still need the documentation row. The
  `cratis.arc.spring-data.jpa.*` and `cratis.arc.spring-data.mongodb.*` switches have no
  `@ConfigurationProperties` class and no metadata file today; their only published description is
  `Documentation/reference/configuration.md`. Do not silently add more properties in that shape.
- Never change an existing default without treating it as a breaking change to consuming
  applications.

## Bean lifecycle, scope, and coroutines

- **Arc work runs in one application-owned scope.** `ArcApplicationCoroutineScope` is registered as
  `@Bean(destroyMethod = "close")` and constructed from `coroutineParallelism` and
  `coroutineQueueCapacity`. It owns a bounded `ThreadPoolExecutor` plus a `Semaphore` admission gate,
  and its `tryLaunch` returns `null` when capacity is exhausted so the host can fail closed. Never
  use `GlobalScope`, `runBlocking` on a request thread, or an unbounded dispatcher.
- **Any bean holding a closable runtime declares `destroyMethod`.** `arcObservableQueryTransport` is
  registered as `@Bean(name = ["arcObservableQueryTransport"], destroyMethod = "close")` for the same
  reason.
- **No `ThreadLocal` for coroutine-visible state, and no request-scoped beans on an Arc path.** A
  coroutine can resume on another thread, so request state is captured *once at transport entry* into
  an explicit immutable object and then passed: `ArcPrincipalFactory` produces an `ArcPrincipal`
  before suspension, and `ArcTenantResolutionService` builds a `TenantResolutionContext` from headers,
  first query-parameter values, host, and claims. Follow that pattern for anything new — do not read
  `SecurityContextHolder` or `RequestContextHolder` after entry.
- **Arc beans are singletons; per-call resolution goes through `ServiceResolver`.**
  `SpringServiceResolver` resolves each generated handler dependency with
  `applicationContext.getBeanProvider(type).ifAvailable`, which is how a prototype- or
  request-scoped application service still works. Do not inject application services directly into
  Arc infrastructure beans.
- **Generated artifacts are discovered once.** `ArcArtifactModules` merges `ServiceLoader`-provided
  and Spring-bean `ArcArtifactModule` instances, deduplicates by concrete class, orders by fully
  qualified class name, and registers them through `ArcArtifactModuleRegistry`. A bean that needs
  artifacts to be registered must depend on `ArcArtifactModules`, not on ordering luck.
- **Fail startup, not the first request, on unrecoverable configuration.** The context deliberately
  fails with messages such as `"Exactly one Arc identity details provider may be registered; found 2"`
  and `"Duplicate Arc POST route '/api/duplicates/same-command'"` that name both offending artifacts.
  Match that quality when adding a startup check.

## Adding or changing an autoconfiguration

1. Decide the layer: host-neutral (`ArcAutoConfiguration`), optional-library
   (`ArcValidationAutoConfiguration`-style), servlet (`ArcWebAutoConfiguration`), or a new
   integration module. Prefer a new bean in an existing class over a new autoconfiguration class.
2. Order it explicitly with `@AutoConfiguration(after = [...])` when it consumes another Arc bean;
   `ArcWebAutoConfiguration` is `after = [ArcAutoConfiguration::class, ArcValidationAutoConfiguration::class]`.
3. Guard every optional dependency with `@ConditionalOnClass`/`@ConditionalOnMissingClass`, keep that
   dependency `compileOnly`, and give the bean a `@ConditionalOnMissingBean` (typed or named) backoff.
4. Register a genuinely new autoconfiguration class in that module's `AutoConfiguration.imports`
   file — an unregistered class is dead code.
5. Test it with Spring Boot's context runners, which is the established convention here:
   `ApplicationContextRunner` for host-neutral wiring and `WebApplicationContextRunner` for servlet
   wiring, composed with `Jackson2AutoConfiguration` (loaded by class name in tests to avoid compiling against Boot's deprecated bridge type), `ArcAutoConfiguration`, and the relevant web/security auto-configurations.
   Cover at least: the default bean is present; an application `withBean(...)` replaces it
   (`assertSame`); property variants behave (`withPropertyValues("cratis.arc.tenancy.resolvers=subdomain", ...)`);
   and invalid configuration fails startup with the exact message
   (`assertThat(context).hasFailed()` plus `hasStackTraceContaining(...)`).
6. Update the `.api` baseline if a public type or bean method signature changed, then
   `Documentation/reference/configuration.md` and the relevant guide.

## Spring Boot 4.1.x is the only supported host baseline

Spring Boot 4 defaults application MVC to Jackson 3, but Arc endpoints inject and write with Jackson
2 directly. The starter therefore publishes `spring-boot-jackson2`, supplies `ArcJacksonModule`, and
applies Arc's wire defaults to Jackson 2 mapper beans without referencing Boot's deprecated Jackson 2
customizer API. Applications that want conventional MVC controllers to use the same Jackson 2 mapper
select `spring.http.converters.preferred-json-mapper=jackson2`; Arc does not silently make that
application-wide decision.

The compatibility module is deprecated for removal in Spring Boot 4.3. Issue #150 owns the deliberate
Jackson 3 public-API migration required before that baseline. Hold unverified Spring Boot minors at
4.1.x rather than discovering bridge removal through a dependency update.

`AGENTS.md`, `README.md`, and `Documentation/reference/parity.md` all state that Spring Boot is the
only host, and the parity
matrix lists **Non-Spring hosting** as *Not planned*: "Spring Boot is the only supported host
integration; Core remains host-independent." Do not add Ktor, Micronaut, Quarkus, a raw servlet
container, or a Spring WebFlux host, and do not add abstractions whose only purpose is to make a
second host possible. `Controllers` are likewise *Not planned* — Arc generates model-bound Spring MVC
endpoints, and a hand-written controller is not the extension mechanism. If a request seems to
require another host, stop and raise it rather than starting one.
