---
applyTo: "**/*"
---

## Java-consumer testing is required for public API changes

Java is a first-class consumer language (`AGENTS.md`). When a change adds or reshapes a public API:

- Add or extend a Java test that calls it the way a Java application would — no Kotlin-only call
  patterns, no `Continuation` parameters, no default-argument reliance.
- Put it next to the existing Java coverage: `Source/src/test/java/io/cratis/arc/conformance/**` for
  the core, the integration's own `src/test/java/**` for a starter,
  `ContractTests/src/test/java/**` for a cross-language contract, and
  `Testing/src/test/java/JavaScenarioConformanceTest.java` for the scenario helpers.
- Update the `.api` baseline in the same change; see [gradle.md](./gradle.md).

A Kotlin-only test for a new public API is an incomplete change.
