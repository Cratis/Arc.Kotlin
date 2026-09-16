---
applyTo: "**/*"
---

## Determinism is a hard requirement

Regenerating from the same manifests must produce **byte-identical** files. This is not a style
preference; a gate proves it on every build.

`:GradlePlugin:verifyContractTestProxyDeterminism` runs a four-task chain defined in
`GradlePlugin/build.gradle.kts`:

1. `generateContractTestProxies` — depends on `:ContractTests:kspTestFixturesKotlin`, runs
   `GenerateArcProxiesCli` against the real `ContractTests` manifest into
   `ContractTests/TypeScript/generated`.
2. `captureContractTestProxyHashes` — writes a sorted `path SHA-256` snapshot of every `.ts` file.
3. `generateContractTestProxiesSecondPass` — regenerates with identical arguments.
4. `verifyContractTestProxyDeterminism` — recomputes the hashes and fails with
   "Contract test TypeScript proxies changed between consecutive generations." on any difference.

Anything that can vary between two runs breaks this gate: a timestamp in output, a set or map
iterated without sorting, a hash over an absolute path, a locale-sensitive comparison. The manifest
side is held to the same standard — see [ksp.md](./ksp.md).
