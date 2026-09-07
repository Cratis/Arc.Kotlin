# The `.ai` directory

This directory is Arc.Kotlin's AI instruction corpus: the rules an AI assistant must hold true while
working in this repository, and the skills that describe how to carry out its recurring tasks. It is
ordinary tracked repository configuration — reviewed, versioned, and changed through pull requests
like any other file here. It is not a session artifact, and it is not an installed copy of something
maintained elsewhere.

## What lives here

```text
.ai/README.md                     this file — ownership, scope, layout, update policy
.ai/rules/general.md              hub: philosophy, authority levels, gates, definition of done, index
.ai/rules/*.md                    invariants: what must always be true in this repository
.ai/skills/<name>/SKILL.md        workflows: how to carry out a recurring task, step by step
```

**Rules define invariants; skills define workflows.** A skill may refine how a rule is applied but
must never contradict one. Start at [rules/general.md](./rules/general.md); it carries the index of
every rule and the quality gates that decide when work is done.

The short authoritative contract is the root [`AGENTS.md`](../AGENTS.md) — platform, module
boundaries, and dependency direction. The files here are the depth behind it. If the two disagree,
`AGENTS.md` wins and the rule file is the stale artifact to fix.

## Ownership

The Arc.Kotlin maintainers own this corpus. Anyone changing repository behavior that an assistant is
expected to follow — a new gate, a moved module boundary, a changed convention — updates the
corresponding rule in the same pull request that changes the behavior.

Organization-wide Cratis AI behavior is authored and approved in `Cratis/AI` and distributed from
there. Improvements found here that should apply across Cratis repositories go back upstream as an
issue or pull request against `Cratis/AI`, not by copying files between repositories.

## `.ai/` and its adapters — one copy, several entry points

Different assistants look for instructions at different paths. This repository satisfies all of them
by pointing symlinks at the single source of truth:

| Path | Role | Points at |
| --- | --- | --- |
| `.ai/` | The only place content lives | — |
| `AGENTS.md` (repository root) | The short contract, read first by every assistant | — |
| `.claude/CLAUDE.md` | Adapter for Claude Code | `.ai/rules/general.md` |
| `.claude/skills` | Skills for Claude Code | `.ai/skills` |
| `.github/copilot-instructions.md` | Adapter for GitHub Copilot | `.ai/rules/general.md` |
| `.github/skills` | Skills for GitHub agents | `.ai/skills` |
| `.agents/skills` | Skills for other agent runtimes | `.ai/skills` |

An adapter is a **symlink**, never a copy. There are no duplicate files to keep in sync, so there is
no way for two versions of a rule to drift apart and no ambiguity about which one is authoritative.
If you ever find real content at an adapter path, move it into `.ai/` and replace it with a symlink.

## Verifying the corpus

```bash
./.ai/verify-corpus.sh
```

It checks that every adapter is a real symlink resolving into `.ai/`, that no adapter path holds a
duplicate copy, that every skill has valid frontmatter whose `name` matches its directory, that every
relative link inside `.ai/` resolves, that `rules/general.md` indexes every rule and skill, that no
work record has been filed here, and that markdownlint is clean. Run it after any change under `.ai/`.

## How an assistant discovers this corpus

1. It reads the root `AGENTS.md` — scope, platform, architecture, and the local-work-artifacts policy.
2. It follows `AGENTS.md` and its own adapter (`.claude/CLAUDE.md`,
   `.github/copilot-instructions.md`) into `.ai/rules/general.md`.
3. `general.md` routes to the specific rule for the area being changed and to `.ai/skills/` — reached
   through `.claude/skills`, `.github/skills`, or `.agents/skills` — for step-by-step workflows.
4. For a recurring task, it invokes the matching skill, which names the exact commands and gates.

## Scope — what is deliberately here

Arc.Kotlin is a **framework and library repository**: it builds Cratis Arc for the JVM. The corpus is
therefore the framework profile, JVM edition, and covers:

- Framework and library design — lovable APIs, defaults, extensibility, no stubs.
- Kotlin implementation style, Java as a first-class consumer language, and the interop shape that
  keeps a public API pleasant from both.
- Gradle module layout, dependency direction, `.api` binary-compatibility baselines, and the build
  gates.
- KSP processors, generated artifacts, and stable diagnostics.
- The Spring Boot integration and the optionality of its dependencies.
- The generated TypeScript proxy contract surface under `ContractTests/TypeScript`.
- Testing across Kotlin, Java, and contract tests.
- Claiming or extending Arc .NET parity, honestly.
- [Documentation authoring and verification](./rules/documentation.md),
  [git and pull requests](./rules/git-and-pull-requests.md),
  [local work artifacts](./rules/local-work-artifacts.md), and
  [managing this corpus](./rules/managing-ai-rules.md).

## Scope — what is deliberately absent

This repository has no C#, .NET, React, EF Core, or Orleans source, and it is not an event-sourced
application. Rules for those exist in other Cratis repositories and are **intentionally not here**:

- No C# or .NET conventions.
- No React, frontend, or UI rules. (There is generated TypeScript for proxy contract tests; there is
  no TypeScript UI.)
- No EF Core or Orleans rules.
- No application-architecture rules — vertical slices, feature folders, MVVM, reactors, reducers,
  read-model-per-feature layouts. Those belong to a consuming application's own `.ai/`, not to the
  framework that application uses.

Do not import them. A rule that has no source in this repository to apply to is noise that dilutes the
rules that do.

## Update policy

- Change a rule in `.ai/rules/`, never in an adapter path.
- Adding a rule means also adding it to the index in [rules/general.md](./rules/general.md); removing
  one means removing every inbound link to it first.
- A skill needs valid YAML frontmatter with `name` (kebab-case, matching its directory) and
  `description` (one sentence starting with when to use it).
- Every factual claim must be verified against this repository's own code, build files, tests,
  workflows, or documentation before it is written. If it cannot be verified, leave it out or mark it
  as unverified.
- Every relative link and every symlink must resolve.
- `./Documentation/verify-markdown.sh` covers `Documentation/**` only. Lint this corpus explicitly
  with `npx markdownlint-cli2 ".ai/**/*.md"`.

The full procedure, including the authority levels a rule may carry, is in
[rules/managing-ai-rules.md](./rules/managing-ai-rules.md).

## Not a distribution package

This corpus is repository-local. Nothing installs it, nothing regenerates it, and nothing overwrites
it from another repository. Do not copy it into another repository, and do not treat a newer copy
elsewhere as authoritative over what is checked in here.
