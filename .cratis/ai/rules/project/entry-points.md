---
applyTo: "**/*"
---

## Entry points

`META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider` names exactly one
provider:

```text
io.cratis.arc.codegeneration.ksp.ArcSymbolProcessorProvider
```

`ArcSymbolProcessorProvider` is the only `public` type in the module; `CodeGeneration/KSP/api/KSP.api`
contains nothing else. `ArcSymbolProcessor` and every helper (`MetadataCollector`,
`ValidationMetadataExtractor`, `JavaRecordParser`, `EnumValueParser`, `Naming`, `Models`,
`ArcDiagnostics`) are `internal`. Keep it that way — widening one of them is a public ABI change.

The processor takes one option, `arc.moduleName`. `validateModuleName` in `Naming.kt` accepts only
`[A-Za-z_][A-Za-z0-9_]*` that is not a Kotlin keyword; anything else is rejected and no module is
generated. Consumers set it through `ksp { arg("arc.moduleName", "...") }`, or through the Gradle
plugin's `cratisArc.moduleName`, which forwards it.
