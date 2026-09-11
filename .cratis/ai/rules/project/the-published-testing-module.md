---
applyTo: "**/*"
---

## The published testing module

`:Testing` (`io.cratis:arc-testing`) is part of the product, not scaffolding. It exposes
`CommandScenario`, `QueryScenario`, `ObservableQueryScenario`, their result types,
`CommandScenarioExtender`, `ScenarioArtifactRegistry`, `ScenarioServiceResolver`, and Java-friendly
blocking and `CompletionStage` facades under `io.cratis.arc.testing.java`. It runs the real
pipelines and does not substitute fake handlers, and JSON round trips are on by default.

Because it is published it has a `.api` baseline (`Testing/api/Testing.api`) and its own Java
conformance test. Treat a change to it as a public API change: design it for both languages, prove
it from Java, and update the baseline deliberately.
