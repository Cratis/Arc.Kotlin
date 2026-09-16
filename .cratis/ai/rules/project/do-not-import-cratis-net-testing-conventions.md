---
applyTo: "**/*"
---

## Do not import Cratis .NET testing conventions

This repository does **not** use, and must not adopt:

- `for_` / `when_` / `and_` specification folder or class hierarchies.
- `Cratis.Specifications`, `Should` extensions, or a given/when/then base-class mandate.
- NSubstitute, Moq, or any .NET mocking idiom translated into JVM form.
- One-behavior-per-class specification splitting.

The JVM house style is a plain JUnit 5 class per unit under test, with backticked Kotlin method names
or camelCase Java method names describing behavior. Carrying .NET test structure into this repository
is a defect, not a stylistic preference.

---

# KSP Code Generation

This rule governs `CodeGeneration/KSP` (`io.cratis:arc-ksp`): what the symbol processor reads, what
it generates, the manifest it emits as a transport contract, its stable `ARCKSP` diagnostic catalog,
and how a processor rule is changed together with its compile tests. Everything here is **framework
contract** unless marked otherwise — the compiler and the build enforce it.
