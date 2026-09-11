# Arc.Kotlin — project context

The JVM implementation of Arc for Kotlin and Java applications hosted by
Spring Boot: compile-time model-bound commands and queries, generated
TypeScript clients, servlet hosting, optional persistence and Chronicle
integrations, OpenAPI, and in-process test support. It does **not** claim
complete parity with Arc on .NET — the implemented and intentionally
unsupported areas are listed in `Documentation/reference/parity.md`.

This is a framework/library repository for Kotlin and Java on Spring Boot —
not an event-sourced application: do not apply C# conventions, application
vertical-slice layouts, or Chronicle event-modeling patterns.

## Project concerns

Read every concern below before working in this repository. Together they are the project-owned instructions and override conflicting shared guidance.

- [Workspace](project/workspace.md)
- [Commands](project/commands.md)
- [Repository rules](project/repository-rules.md)
- [What "Arc .NET" is](project/what-arc-net-is.md)
- [The rules](project/the-rules.md)
- [Editing `parity.md`](project/editing-parity-md.md)
- [Reporting parity to a human](project/reporting-parity-to-a-human.md)
- [Branching](project/branching.md)
- [Commit messages](project/commit-messages.md)
- [Before opening a pull request](project/before-opening-a-pull-request.md)
- [Release-intent label — required on every pull request](project/release-intent-label-required-on-every-pull-request.md)
- [How a release is cut](project/how-a-release-is-cut.md)
- [Merge policy — always a real merge commit](project/merge-policy-always-a-real-merge-commit.md)
- [Never rewrite published history](project/never-rewrite-published-history.md)
- [Work records never enter git](project/work-records-never-enter-git.md)
- [Where tests live](project/where-tests-live.md)
- [Naming](project/naming.md)
- [JUnit 5 idioms used here](project/junit-5-idioms-used-here.md)
- [Unit tests versus contract tests](project/unit-tests-versus-contract-tests.md)
- [Java-consumer testing is required for public API changes](project/java-consumer-testing-is-required-for-public-api-changes.md)
- [KSP compile-testing fixtures](project/ksp-compile-testing-fixtures.md)
- [Test fixtures and extra source sets](project/test-fixtures-and-extra-source-sets.md)
- [The published testing module](project/the-published-testing-module.md)
- [Do not import Cratis .NET testing conventions](project/do-not-import-cratis-net-testing-conventions.md)
- [Entry points](project/entry-points.md)
- [What the processor reads](project/what-the-processor-reads.md)
- [What the processor generates](project/what-the-processor-generates.md)
- [The manifest is a transport contract](project/the-manifest-is-a-transport-contract.md)
- [Diagnostics](project/diagnostics.md)
- [Changing or adding a processor rule](project/changing-or-adding-a-processor-rule.md)
- [How generated output feeds the rest of the build](project/how-generated-output-feeds-the-rest-of-the-build.md)
- [Dependency direction is one-way](project/dependency-direction-is-one-way.md)
- [Registered autoconfigurations](project/registered-autoconfigurations.md)
- [Optionality is expressed with conditions, never with a hard dependency](project/optionality-is-expressed-with-conditions-never-with-a-hard-dependency.md)
- [Configuration properties](project/configuration-properties.md)
- [Bean lifecycle, scope, and coroutines](project/bean-lifecycle-scope-and-coroutines.md)
- [Adding or changing an autoconfiguration](project/adding-or-changing-an-autoconfiguration.md)
- [Spring Boot 4.1.x is the only supported host baseline](project/spring-boot-4-1-x-is-the-only-supported-host-baseline.md)
- [What generates proxies](project/what-generates-proxies.md)
- [The generated-file contract](project/the-generated-file-contract.md)
- [Determinism is a hard requirement](project/determinism-is-a-hard-requirement.md)
- [Strict-mode compilation](project/strict-mode-compilation.md)
- [The runtime TAP gate and its exact totals](project/the-runtime-tap-gate-and-its-exact-totals.md)
- [Tracked sources versus generated output](project/tracked-sources-versus-generated-output.md)
- [What to re-run after a change that can move generated output](project/what-to-re-run-after-a-change-that-can-move-generated-output.md)
- [Scope boundaries](project/scope-boundaries.md)
- [AI-assisted development](project/ai-assisted-development.md)
