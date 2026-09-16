---
applyTo: "**/*"
---

## How a release is cut

- `publish.yml` runs on a push to `main` whose changes touch `Source/**`, `CodeGeneration/KSP/**`,
  `GradlePlugin/**`, the six `Integrations/**` modules, `Testing/**`, `build.gradle.kts`,
  `settings.gradle.kts`, or `gradle/**`. A merge that touches only `Documentation/**` or `.github/**`
  does not trigger it at all.
- It first re-verifies (`./gradlew clean build --no-configuration-cache` and
  `./Documentation/verify-markdown.sh`) plus the Chronicle real-kernel workflow, then `cratis/release-action`
  derives the version from the merged pull request's release-intent label, creates the GitHub release,
  and Gradle publishes the signed artifacts to Maven Central.
- **Serialize every merge that triggers `publish.yml`.** Before merging a pull request that touches
  any path listed above, confirm the latest `publish.yml` run is completed. After merging, wait for
  the resulting publish run to complete before merging another pull request that touches those paths.
  This applies even to `no-release`: its verification run occupies the same concurrency group.
  GitHub Actions keeps only one pending run per concurrency group even with
  `cancel-in-progress: false`; a newer pending run cancelled an older version-labelled run in #133.
  Do not treat the concurrency setting as a FIFO queue.
- A release can also be cut manually with `workflow_dispatch`, supplying an explicit version and
  release notes.
- Do not hand-edit versions: the local checkout builds as `0.0.0-SNAPSHOT` unless Gradle receives
  `-Pversion`.
