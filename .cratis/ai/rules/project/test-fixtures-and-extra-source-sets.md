---
applyTo: "**/*"
---

## Test fixtures and extra source sets

`ContractTests` applies `` `java-test-fixtures` ``. Fixtures are shared artifacts, not test-only
helpers: KSP is applied to the fixtures source set with `add("kspTestFixtures", project(":CodeGeneration:KSP"))`
and `ksp { arg("arc.moduleName", "ContractTests") }`, so the fixtures are what produce the manifest
the proxy generator reads. A new fixture therefore changes generated TypeScript output — re-run the
proxy gates from [typescript-proxies.md](./typescript-proxies.md).

`chronicleRealKernelTest` is a separately created source set with its own `Test` task. It is
deliberately outside `check`, uses Testcontainers against a digest-pinned Chronicle image, and fails
rather than skips when Docker is unavailable. Do not wire it into `check`.
