---
applyTo: "**/*"
---

## Unit tests versus contract tests

Put a test in the owning module when it verifies that module's own behavior in isolation: a route
calculation, a descriptor's equality and immutability, an auto-configuration bean graph, a
processor's naming function, a proxy renderer's output.

Put it in `ContractTests` when it verifies the **public interoperability surface across languages or
across generation stages**:

- Generated command handlers and query performers executing through the real
  `DefaultCommandPipeline` / `DefaultQueryPipeline`.
- The generated `META-INF/cratis/arc/*.json` manifest being deterministic, complete, and
  timestamp-free.
- Kotlin and Java artifacts behaving identically for the same shape.
- Generated TypeScript proxy text, which is asserted from Kotlin in
  `GeneratedTypeScriptProxiesTest` using the `arc.contractTests.generatedProxies` system property.
  That test task depends on `:GradlePlugin:generateContractTestProxies`.

`ContractTests` is unpublished by design. Never move a fixture from it into a published module to
make an import work.
