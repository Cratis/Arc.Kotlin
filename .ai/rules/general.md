---
description: Always-on project instructions for Cratis Arc.Kotlin.
alwaysApply: true
---

# Cratis Arc.Kotlin — Project Instructions

Arc.Kotlin is a **framework repository**: it *builds* Cratis Arc for the JVM. It is **not** an
event-sourced application, and the Cratis *application* rules — vertical slices, feature folders,
MVVM frontends, read-model-per-feature layouts — **do not apply here and must not be imposed**.

The corpus in this repository is the **framework profile, JVM edition**. Rules that exist in other
Cratis repositories for C#, .NET, React, EF Core, and Orleans are deliberately **absent** — this
repository has no such source. See [managing-ai-rules.md](./managing-ai-rules.md) before adding,
renaming, or removing anything under `.ai/`.

## Project Philosophy

Every rule here serves **ease of use**, **productivity**, and **maintainability**:

- **Lovable APIs** — APIs should be pleasant to use: sane defaults, flexible, extensible,
  overridable. If an API feels awkward, it is wrong. In a framework repository this is the product.
- **Easy to do things right, hard to do things wrong** — convention over configuration; artifact
  discovery by annotation and naming; minimal boilerplate. Guide the consumer into the pit of success.
- **Two first-class consumer languages** — Kotlin *and* Java. An API that is only pleasant from
  Kotlin is an unfinished API. See [kotlin-java-interop.md](./kotlin-java-interop.md).
- **Compile-time over reflection** — KSP generates reflection-free artifacts. Prefer moving work to
  compile time with a stable diagnostic over discovering the same mistake at runtime.
- **Specialization over reuse** — focused, purpose-built types over one type stretched across
  conflicting scenarios.
- **Consistency is king** — when in doubt, follow the established pattern in the area you are editing.

When these instructions don't cover a situation, apply these values to make the call.

## Three Levels of Authority

Every rule is one of three kinds — know which, because they carry different weight:

- **Framework contract** — enforced by the compiler, KSP, `apiCheck`, or runtime. Violating it breaks
  the build or behaves wrongly.
- **Repository convention** — the house default for consistency. Not machine-enforced, but follow it.
- **Product policy** — belongs in a consuming application's own instructions, not here.

Where a rule is convention rather than contract, say so. Do not claim "the framework requires this"
for a convention.

## Platform and Architecture

Authoritative platform facts, module boundaries, and dependency direction live in
[`AGENTS.md`](../../AGENTS.md) at the repository root — JDK 17, Gradle 8.14.4, Kotlin implementation,
Java as a first-class consumer, Spring Boot 4.1.x as the supported host baseline, Jackson for JSON, no `ThreadLocal` for
coroutine-visible state, warnings as errors, JUnit 5, binary-compatibility baselines.

`AGENTS.md` is the short contract; the files in this folder are the depth behind it. If the two ever
disagree, `AGENTS.md` wins and the rule file is the stale artifact to fix.

## Collaboration Default

Default to agentic behavior: inspect the rules, skills, code, tests, and generated output; make
conservative assumptions supported by that context; implement and verify end to end when feasible.
Don't interrupt with questions the repository can answer. Ask when the answer can't be found locally,
when reasonable design choices differ meaningfully, when a change is risky, or when checkpoints were
requested.

## Verification Discipline

A claim is only as good as the signal behind it — a Gradle result, a test run, an `apiCheck` pass,
observed sample behavior — not the model's own confidence. Internal reasoning *plans* the work;
external signals *confirm* it.

- **Confirm "done"/"fixed"/"correct" against a fresh signal — never self-assessment.**
- **After a fix, re-run the gate that failed.** Don't argue yourself to green.
- **A green build is not behavioral correctness.** Compilation proves it compiles. Tests, contract
  tests, and running a sample prove behavior.
- **Report with inspectable evidence, and name what you did not verify.**
- **Never claim parity with Arc .NET that has not been demonstrated.** See
  [arc-parity.md](./arc-parity.md).

## Quality Gates

| Gate | Command | Pass criteria |
| --- | --- | --- |
| Full workspace | `./gradlew build --no-configuration-cache -x :ContractTests:typeScriptRuntimeTest` | zero errors, zero warnings |
| Binary compatibility | `./gradlew apiCheck` | no unintended `.api` diff; deliberate changes land as an `apiDump` in the same commit |
| Proxy determinism | `./gradlew :GradlePlugin:verifyContractTestProxyDeterminism :ContractTests:typeScriptBuild --no-configuration-cache` | deterministic regeneration, strict-mode compile |
| TypeScript runtime | `./gradlew :ContractTests:typeScriptRuntimeTest --no-configuration-cache` | exact TAP totals, zero fail/skip/todo |
| Documentation | `./Documentation/verify-markdown.sh` | lint, snippet validation, toc and link checks pass |
| AI corpus | `./.ai/verify-corpus.sh` | adapters, skill frontmatter, links, the rule index, and lint are clean |

Run affected-project incremental checks after a coherent change, then targeted regression tests for the changed behavior. Re-run a failed gate after a relevant fix. Reserve wider matrices and clean/Release builds for cross-cutting changes, demonstrated stale outputs, or required merge/release gates. Documentation/rule-only edits need relevant Markdown, frontmatter, link, and corpus checks, not an application build. Diagnose unrelated or environmental failures within a bounded attempt; report the evidence and blocker instead of broadening scope or retrying indefinitely. Required gates remain blocking until satisfied; never silently waive red CI.

Documentation-only changes use repository-supported non-release intent, ordinarily `no-release`; confirm the workflow contract rather than assuming a label or API state. Run relevant content, link, frontmatter, and corpus checks instead of unrelated application builds, and satisfy every repository-required check, including release-intent checks where supported. Documentation is never a blanket exemption from red CI.

Gradle needs a JDK 17 toolchain on `PATH`. If `./gradlew` reports it cannot locate a Java runtime,
point `JAVA_HOME` at a local JDK 17 for the command rather than changing anything in the repository.

## Definition of Done

- Every affected module builds with zero warnings and zero errors.
- Tests for every affected module pass, including `ContractTests` when a public contract moved.
- Public API changes are reflected in the checked-in `.api` baselines.
- Public-facing changes update `Documentation/` and its verification passes.
- Behavior claimed in `Documentation/reference/parity.md` is actually demonstrated by a test,
  contract test, or runnable sample.
- No placeholder behavior, fake implementation, or no-op stub was added to make a gate pass.

## Where to Look

| For | Location |
| --- | --- |
| Platform, module boundaries, dependency direction | [`AGENTS.md`](../../AGENTS.md) (repository root) |
| Framework/library design principles | [framework.md](./framework.md) |
| Kotlin style and conventions | [kotlin.md](./kotlin.md) |
| Java sources, fixtures, and samples | [java.md](./java.md) |
| Public API shape for both languages | [kotlin-java-interop.md](./kotlin-java-interop.md) |
| Tests | [testing.md](./testing.md) |
| Build, modules, `.api` baselines | [gradle.md](./gradle.md) |
| Spring Boot integration, starters, optionality | [spring-boot.md](./spring-boot.md) |
| KSP processors and generated artifacts | [ksp.md](./ksp.md) |
| Generated proxies and the TypeScript contract surface | [typescript-proxies.md](./typescript-proxies.md) |
| Claiming or extending Arc .NET parity | [arc-parity.md](./arc-parity.md) |
| Code quality principles | [code-quality.md](./code-quality.md) |
| Documentation authoring | [documentation.md](./documentation.md) |
| Commits, branches, pull requests | [git-and-pull-requests.md](./git-and-pull-requests.md) |
| Terminology | [glossary.md](./glossary.md) |
| Local AI work artifacts | [local-work-artifacts.md](./local-work-artifacts.md) |
| Changing this corpus | [managing-ai-rules.md](./managing-ai-rules.md) |
| Step-by-step workflows | [`.ai/skills/`](../skills/) |

## Skills

Workflows live in [`.ai/skills/`](../skills/). Reach for one when the task matches:

| Skill | Use it when |
| --- | --- |
| [kotlin-framework-development](../skills/kotlin-framework-development/SKILL.md) | implementing or changing framework behavior in Kotlin |
| [java-consumer-api](../skills/java-consumer-api/SKILL.md) | proving an API is correct and pleasant from Java |
| [kotlin-java-interop-review](../skills/kotlin-java-interop-review/SKILL.md) | reviewing a public surface both languages consume |
| [arc-to-jvm-porting](../skills/arc-to-jvm-porting/SKILL.md) | porting a feature or behavior from Arc .NET |
| [ksp-code-generation](../skills/ksp-code-generation/SKILL.md) | changing the KSP processor or what it generates |
| [typescript-proxy-contracts](../skills/typescript-proxy-contracts/SKILL.md) | changing anything the generated proxies or TS gates see |
| [jvm-testing](../skills/jvm-testing/SKILL.md) | adding or fixing tests |
| [documentation-authoring](../skills/documentation-authoring/SKILL.md) | writing or changing a `Documentation/` page |
| [ship-changes](../skills/ship-changes/SKILL.md) | committing, pushing, opening or landing a pull request |

## Source-of-Truth Discipline

- **Rules define invariants; skills define workflows.** A skill may refine how to apply a rule but
  must not contradict it. On conflict, follow the stricter invariant and fix the stale artifact.
- Only make high-confidence suggestions.
- Do not change dependency manifests, version catalogs, the Gradle wrapper, or `gradle.properties`
  unless explicitly asked.
- When asked to commit, push, open a PR, ship, or land changes, use the
  [ship-changes](../skills/ship-changes/SKILL.md) skill.
- Do not copy `.ai`, `.agents`, `.claude`, or `.github` trees from this repository into another one.
  Shared Cratis AI behavior is authored and approved in `Cratis/AI` and distributed from there;
  improvements found here go back as an issue or pull request against that repository.

## General

- **American English** in all code, comments, and documentation (initialize, behavior, color,
  serialize…).
- Treat warnings as errors; never suppress warning output to get a green build.
- Reuse the active terminal for commands; open a new one only when the current one is busy.
- Every source and build file starts with the standard Cratis header:

```kotlin
// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.
```

## Local AI work artifacts — `.ai-work/` only

AI-assisted sessions produce working artifacts: plans, handover documents, session notes,
continuation prompts, status boards, scratch analyses, research dumps. These are **work records, not
documentation** — see [local-work-artifacts.md](./local-work-artifacts.md). Create every such
artifact inside `.ai-work/` at the repository root, never anywhere else, and never commit it.
`.pi/tasks/` and `.pi/delegate/` are local execution artifacts and must also remain untracked.
