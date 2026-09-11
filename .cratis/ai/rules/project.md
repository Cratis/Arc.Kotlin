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

- [Workspace](.cratis/ai/rules/project/workspace.md)
- [Commands](.cratis/ai/rules/project/commands.md)
- [Repository rules](.cratis/ai/rules/project/repository-rules.md)
- [What "Arc .NET" is](.cratis/ai/rules/project/what-arc-net-is.md)
- [The rules](.cratis/ai/rules/project/the-rules.md)
- [Editing `parity.md`](.cratis/ai/rules/project/editing-parity-md.md)
- [Reporting parity to a human](.cratis/ai/rules/project/reporting-parity-to-a-human.md)
- [Branching](.cratis/ai/rules/project/branching.md)
- [Commit messages](.cratis/ai/rules/project/commit-messages.md)
- [Before opening a pull request](.cratis/ai/rules/project/before-opening-a-pull-request.md)
- [Release-intent label — required on every pull request](.cratis/ai/rules/project/release-intent-label-required-on-every-pull-request.md)
- [How a release is cut](.cratis/ai/rules/project/how-a-release-is-cut.md)
- [Merge policy — always a real merge commit](.cratis/ai/rules/project/merge-policy-always-a-real-merge-commit.md)
- [Never rewrite published history](.cratis/ai/rules/project/never-rewrite-published-history.md)
- [Work records never enter git](.cratis/ai/rules/project/work-records-never-enter-git.md)
- [Where tests live](.cratis/ai/rules/project/where-tests-live.md)
- [Naming](.cratis/ai/rules/project/naming.md)
- [JUnit 5 idioms used here](.cratis/ai/rules/project/junit-5-idioms-used-here.md)
- [Unit tests versus contract tests](.cratis/ai/rules/project/unit-tests-versus-contract-tests.md)
- [Java-consumer testing is required for public API changes](.cratis/ai/rules/project/java-consumer-testing-is-required-for-public-api-changes.md)
- [KSP compile-testing fixtures](.cratis/ai/rules/project/ksp-compile-testing-fixtures.md)
- [Test fixtures and extra source sets](.cratis/ai/rules/project/test-fixtures-and-extra-source-sets.md)
- [The published testing module](.cratis/ai/rules/project/the-published-testing-module.md)
- [Do not import Cratis .NET testing conventions](.cratis/ai/rules/project/do-not-import-cratis-net-testing-conventions.md)
- [Entry points](.cratis/ai/rules/project/entry-points.md)
- [What the processor reads](.cratis/ai/rules/project/what-the-processor-reads.md)
- [What the processor generates](.cratis/ai/rules/project/what-the-processor-generates.md)
- [The manifest is a transport contract](.cratis/ai/rules/project/the-manifest-is-a-transport-contract.md)
- [Diagnostics](.cratis/ai/rules/project/diagnostics.md)
- [Changing or adding a processor rule](.cratis/ai/rules/project/changing-or-adding-a-processor-rule.md)
- [How generated output feeds the rest of the build](.cratis/ai/rules/project/how-generated-output-feeds-the-rest-of-the-build.md)
- [Dependency direction is one-way](.cratis/ai/rules/project/dependency-direction-is-one-way.md)
- [Registered autoconfigurations](.cratis/ai/rules/project/registered-autoconfigurations.md)
- [Optionality is expressed with conditions, never with a hard dependency](.cratis/ai/rules/project/optionality-is-expressed-with-conditions-never-with-a-hard-dependency.md)
- [Configuration properties](.cratis/ai/rules/project/configuration-properties.md)
- [Bean lifecycle, scope, and coroutines](.cratis/ai/rules/project/bean-lifecycle-scope-and-coroutines.md)
- [Adding or changing an autoconfiguration](.cratis/ai/rules/project/adding-or-changing-an-autoconfiguration.md)
- [Spring Boot 4.1.x is the only supported host baseline](.cratis/ai/rules/project/spring-boot-4-1-x-is-the-only-supported-host-baseline.md)
- [What generates proxies](.cratis/ai/rules/project/what-generates-proxies.md)
- [The generated-file contract](.cratis/ai/rules/project/the-generated-file-contract.md)
- [Determinism is a hard requirement](.cratis/ai/rules/project/determinism-is-a-hard-requirement.md)
- [Strict-mode compilation](.cratis/ai/rules/project/strict-mode-compilation.md)
- [The runtime TAP gate and its exact totals](.cratis/ai/rules/project/the-runtime-tap-gate-and-its-exact-totals.md)
- [Tracked sources versus generated output](.cratis/ai/rules/project/tracked-sources-versus-generated-output.md)
- [What to re-run after a change that can move generated output](.cratis/ai/rules/project/what-to-re-run-after-a-change-that-can-move-generated-output.md)
- [Scope boundaries](.cratis/ai/rules/project/scope-boundaries.md)
- [AI-assisted development](.cratis/ai/rules/project/ai-assisted-development.md)
