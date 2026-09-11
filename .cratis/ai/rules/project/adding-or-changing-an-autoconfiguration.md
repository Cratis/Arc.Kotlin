---
applyTo: "**/*"
---

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
   wiring, composed with Boot's native `JacksonAutoConfiguration`, `ArcAutoConfiguration`, and the relevant web/security auto-configurations.
   Cover at least: the default bean is present; an application `withBean(...)` replaces it
   (`assertSame`); property variants behave (`withPropertyValues("cratis.arc.tenancy.resolvers=subdomain", ...)`);
   and invalid configuration fails startup with the exact message
   (`assertThat(context).hasFailed()` plus `hasStackTraceContaining(...)`).
6. Update the `.api` baseline if a public type or bean method signature changed, then
   `Documentation/reference/configuration.md` and the relevant guide.
