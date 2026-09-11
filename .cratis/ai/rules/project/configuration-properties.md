---
applyTo: "**/*"
---

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
