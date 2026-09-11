---
applyTo: "**/*"
---

## Workspace

Gradle multi-project build (`settings.gradle.kts` holds the module list);
published identities are `io.cratis:…`. Gradle needs a JDK 17 on
`JAVA_HOME`/`PATH` — export it before `./gradlew` if it cannot find one.
The `.api` public-surface baselines are enforced; a public API change that
survives review updates them deliberately.
