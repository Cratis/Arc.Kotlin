---
applyTo: "**/*"
---

## Naming

Class naming is **not uniform across modules**, and that is the observed state. Match the module you
are editing rather than imposing one style:

- `*Test.kt` in `Source`, `CodeGeneration/KSP`, `GradlePlugin`, and `ContractTests`.
- `*Tests.kt` in all six `Integrations/**` modules and in `Testing`.
- Java: `*Test.java` in `Source`, `ContractTests`, `Integrations/SpringBoot`,
  `Integrations/Chronicle`, `Integrations/Observability`, and `Testing`;
  `*Tests.java` in `Integrations/SpringDataJpa` and `Integrations/SpringDataMongo`.
- Java consumer tests carry an explicit marker in the name — `...JavaConformanceTest`,
  `...JavaContractTest`, or a `Java`-prefixed class — so the Java-side coverage is findable.

This is a repository convention. It is not machine-enforced; consistency within a module is what
matters.
