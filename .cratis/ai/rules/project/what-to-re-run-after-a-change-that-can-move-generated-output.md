---
applyTo: "**/*"
---

## What to re-run after a change that can move generated output

Any of these can move generated proxies: a change to `ArcSymbolProcessor` or the manifest; a new,
renamed, or reshaped fixture under `ContractTests/src/testFixtures/**`; a change to a sample's
commands, read models, or queries; a change to `TypeScriptProxyGenerator`, `ArcManifestDiscovery`, or
the endpoint route helpers; a change to `ApiEndpointOptions` defaults or to a generation task's
arguments.

Run, in this order:

```bash
./gradlew clean build --no-configuration-cache -x :ContractTests:typeScriptRuntimeTest
./gradlew :GradlePlugin:verifyContractTestProxyDeterminism :ContractTests:typeScriptBuild --no-configuration-cache
./gradlew :ContractTests:typeScriptRuntimeTest --no-configuration-cache
```

Then, if the runtime behavior of a generated client changed, inspect the regenerated files under
`ContractTests/TypeScript/generated/` and `Samples/*/*/build/generated/arc-proxies/` and confirm the
diff is what you intended. `GeneratedTypeScriptProxiesTest` in `ContractTests` asserts on that text
from Kotlin and is the right place to lock in a new expectation.

Node 22 and npm are required for the TypeScript gates; CI pins Node 22 with the lockfile as the cache
key. If npm is unavailable locally, say the gate was not run rather than reporting it green.
