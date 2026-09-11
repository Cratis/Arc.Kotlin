---
applyTo: "**/*"
---

## KSP compile-testing fixtures

`:CodeGeneration:KSP` tests use `dev.zacsweers.kctfork:ksp` with `useKsp2()`,
`symbolProcessorProviders`, `kspWithCompilation`, and `kspProcessorOptions`. Two kinds of cases exist
and both are required when you change a processor rule.

Positive cases declare their sources inline and assert on generated output:

```kotlin
@OptIn(ExperimentalCompilerApi::class)
internal class ArcSymbolProcessorCommandResponseCompilationTest {
    @Test
    fun `Kotlin and Java aggregates produce ordered response metadata`() {
        val result = compile(listOf(SourceFile.kotlin("ResponseFixtures.kt", """ ... """)))
        // assert on exit code, generated sources, and manifest content
    }
}
```

The negative case is a single test over the whole fixture tree. It walks
`ContractTests/src/negativeFixtures` (supplied as `arc.contractNegativeFixtures` from
`CodeGeneration/KSP/build.gradle.kts`), compiles every `.kt` and `.java` file together, asserts
`KotlinCompilation.ExitCode.COMPILATION_ERROR`, and then asserts that specific `[ARCKSPxxxx]` codes
and specific message fragments appear in `result.messages`. To add a negative rule:

1. Add the offending fixture under `ContractTests/src/negativeFixtures/{kotlin,java}/`.
2. Add its diagnostic code to the assertion list in
   `ArcSymbolProcessorNegativeCompilationTest`, and assert a distinctive message fragment so the
   test fails if the diagnostic degrades into a generic one.
3. Run `./gradlew :CodeGeneration:KSP:test`.

`ArcDiagnosticReferenceTest` asserts that `CodeGeneration/KSP/DIAGNOSTICS.md` is byte-equal to
`ArcDiagnostic.referenceMarkdown()`. Changing the catalog without regenerating that file fails the
test. See [ksp.md](./ksp.md).
