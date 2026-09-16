---
applyTo: "**/*"
---

## What generates proxies

One renderer, reached two ways:

- `GenerateArcProxies` — the `generateArcProxies` task registered by the `io.cratis.arc` plugin
  (`GradlePlugin/src/main/kotlin/io/cratis/arc/gradle/ArcGradlePlugin.kt`). It is wired into `build`,
  is skipped until `cratisArc.proxies.outputDirectory` is set, and reads the main output, compile
  classpath, and runtime classpath.
- `io.cratis.arc.gradle.GenerateArcProxiesCli` — the command-line entry point used by the sample and
  contract-test generation tasks in this repository, which register their own `JavaExec` tasks rather
  than applying the plugin to themselves.

Both call `ArcManifestDiscovery.discover` / `merge` and then `TypeScriptProxyGenerator`. There is no
second renderer; do not add one.

CLI options, verified in `GenerateArcProxiesCli`: `--manifest-classpath` (repeatable),
`--output-directory`, `--route-prefix`, `--route-segments-to-skip`, `--include-command-names`,
`--include-query-names`, `--enable-query-http-method`, `--remove-stale-generated-files`,
`--proxy-segments-to-skip`. Unknown options are a hard failure.
