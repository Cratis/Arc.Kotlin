---
applyTo: "**/*"
---

## Where tests live

| Location | Contains |
| --- | --- |
| `Source/src/test/kotlin/**` | Host-agnostic runtime unit tests |
| `Source/src/test/java/**` | Java conformance tests for the core public surface |
| `Integrations/<Name>/src/test/{kotlin,java}/**` | Integration unit tests, mostly Spring context tests |
| `Testing/src/test/{kotlin,java}/**` | Tests for the published scenario helpers |
| `CodeGeneration/KSP/src/test/kotlin/**` | KSP compile-testing and pure unit tests |
| `GradlePlugin/src/test/kotlin/**` | Manifest discovery, proxy rendering, and differential tests |
| `ContractTests/src/test/{kotlin,java}/**` | Cross-language contracts over generated artifacts |
| `ContractTests/src/testFixtures/{kotlin,java}/**` | Commands, read models, and models KSP processes |
| `ContractTests/src/negativeFixtures/{kotlin,java}/**` | Sources that must **fail** compilation |
| `ContractTests/src/chronicleRealKernelTest/kotlin/**` | Docker-backed kernel gate, outside `check` |
| `ContractTests/TypeScript/contracts/**` | TypeScript type and runtime contracts |

`ContractTests/src/negativeFixtures` is not a compiled Gradle source set. It is read as plain text by
`:CodeGeneration:KSP`'s tests through the `arc.contractNegativeFixtures` system property. Adding a
file there changes the KSP negative test, not the `ContractTests` compilation.
