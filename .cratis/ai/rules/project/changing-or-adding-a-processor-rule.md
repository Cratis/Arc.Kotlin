---
applyTo: "**/*"
---

## Changing or adding a processor rule

1. Decide the authority level. If generated code cannot honor the shape, it is a framework contract
   and needs an error. If it merely risks a mistake, it is a warning (`ARCKSP0100`, `ARCKSP0107`,
   `ARCKSP0400` are the existing precedents).
2. Reuse the closest existing `ArcDiagnostic`. Add a new entry only for a genuinely new category, at
   the end of its numeric range.
3. Implement the check in `ArcSymbolProcessor` (or the relevant collector/extractor) and report with
   the explicit diagnostic overload plus the node.
4. Add a **positive** compile test proving the supported shape still generates correct output, in the
   matching `ArcSymbolProcessor*CompilationTest`.
5. Add a **negative** fixture under `ContractTests/src/negativeFixtures/{kotlin,java}/` and register
   its code, plus a distinctive message fragment, in `ArcSymbolProcessorNegativeCompilationTest`.
   Both languages when the rule applies to both.
6. If you added or changed a catalog entry, regenerate `CodeGeneration/KSP/DIAGNOSTICS.md` so it
   matches `ArcDiagnostic.referenceMarkdown()` exactly.
7. Run `./gradlew :CodeGeneration:KSP:test`, then the workspace gate, then the proxy gates if the
   manifest or generated shapes moved.

See [testing.md](./testing.md) for the compile-testing harness details.
