---
applyTo: "**/*"
---

## Registered autoconfigurations

Exactly five classes are listed in
`Integrations/SpringBoot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
Know which layer you are editing:

| Class | Guarded by | Owns |
| --- | --- | --- |
| `ArcAutoConfiguration` | none (host-neutral) | registries, pipelines, authentication, authorization, tenancy resolution, introspection, artifact modules, coroutine scope, Jackson 3 wiring |
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
