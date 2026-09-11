---
applyTo: "**/*"
---

## Tracked sources versus generated output

| Path | State |
| --- | --- |
| `ContractTests/TypeScript/contracts/**` | Tracked. Hand-written type and runtime contracts |
| `ContractTests/TypeScript/package.json`, `package-lock.json`, `tsconfig.json` | Tracked and pinned |
| `ContractTests/TypeScript/generated/**` | **Gitignored.** A clean build fixture, never a golden file |
| `ContractTests/TypeScript/node_modules/**` | Gitignored |
| `Samples/*/*/build/generated/arc-proxies/**` | Build output; synced into `TypeScript/generated/` |
| `GradlePlugin/src/test/resources/differential/dotnet/**` | Tracked. The .NET differential fixture |

`prepareRuntimeProxies` syncs the Kotlin Spring Boot sample's proxies into
`TypeScript/generated/runtime`; `prepareChronicleSampleProxies` syncs the Kotlin and Java Chronicle
sample proxies into `TypeScript/generated/chronicle/{kotlin,java}`. Never commit anything under
`ContractTests/TypeScript/generated/`, and never hand-edit it — the next generation deletes or
overwrites it.

The sample build files additionally assert the exact set of expected proxy file names in a `doLast`
block, and `Samples/Java/ChronicleSpringBoot` asserts that a response-less command renders as
`extends Command<ICreateTask>` and not `extends Command<ICreateTask,`. Renaming or removing a sample
artifact means updating those lists in the same change.
