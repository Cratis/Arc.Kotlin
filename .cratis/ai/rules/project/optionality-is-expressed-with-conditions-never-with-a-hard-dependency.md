---
applyTo: "**/*"
---

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
  `JsonMapperBuilderCustomizer`, `FilterRegistrationBean`, `SimpleUrlHandlerMapping`). The Jackson
  defaults bean retains the historical `arcJacksonCustomizer` name while using Boot 4's native,
  non-deprecated Jackson 3 builder customizer.
  Getting this wrong silently disables Arc's own bean; a bean-backoff test is required.
- **Collect application contributions with `ObjectProvider<T>.orderedStream()`**, so Spring `@Order`
  is the complete and documented precedence rule, as `arcCommandPipeline`, `arcQueryRenderers`, and
  `arcAuthentication` all do.
