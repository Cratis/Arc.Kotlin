# Managing the AI rules corpus

This rule governs changes to the AI instruction corpus itself: what lives under `.ai/`, how rules and
skills differ, what you must do when adding, renaming, or removing one, what makes a change valid, and
why this corpus must not be copied into another repository. Read it before touching anything under
`.ai/`, `.claude/`, `.agents/`, or the AI-facing files in `.github/`.

## `.ai/` is the single source of truth

```text
.ai/README.md                     ownership and scope of this corpus
.ai/rules/*.md                    invariants
.ai/skills/<name>/SKILL.md        workflows
.claude/  .agents/  .github/      symlink adapters into .ai/
```

- Every rule and every skill has exactly **one** file, and it lives under `.ai/`.
- `.claude/`, `.agents/`, and the AI-facing entries in `.github/` contain **symlinks only**. They
  exist so different assistants find the same content at the path they expect.
- **Never create a divergent copy.** If you find real content in an adapter location, the fix is to
  move it into `.ai/` and replace the original with a symlink — not to keep both in sync by hand.
- Content edits happen in `.ai/`. Adapter changes are limited to adding, retargeting, or removing a
  symlink.
- The root `AGENTS.md` is the short authoritative contract. `.ai/rules/` is the depth behind it. If
  they disagree, `AGENTS.md` wins and the rule file is the stale artifact to fix.

## Rules define invariants; skills define workflows

- A **rule** states what must always be true — a constraint, a boundary, a required shape. It is
  written to be checked against a change.
- A **skill** states how to carry out a recurring task, step by step, including which gates to run.
- A skill may refine how a rule is applied. **A skill must never contradict a rule.** On a conflict,
  follow the stricter invariant and fix the stale artifact in the same change.
- Do not restate a rule's substance inside a skill. Link to the rule instead, so there is one place to
  correct.

## Adding a rule

1. Create `.ai/rules/<kebab-case-name>.md`.
2. Open with an `# H1` and one paragraph stating exactly what the file governs and what it does not.
3. Write prescriptively for an agent that will act on it: short rules, concrete commands, and a real
   example from this repository in a fenced block with a language.
4. **Add it to the index in [general.md](./general.md)** — a rule that no index points at will not be
   found. Add it to the "Where to Look" table with a one-line description of when to read it.
5. Link to related rules as plain siblings, `[kotlin.md](./kotlin.md)`, and to the root contract as
   `[AGENTS.md](../../AGENTS.md)`.

## Removing or renaming a rule

- Removing a rule means **removing every inbound link** to it first — from `general.md`, from other
  rules, from `.ai/README.md`, from skills, and from `AGENTS.md` if it is mentioned there. A dangling
  relative link is a defect.
- Renaming is a remove plus an add: update every inbound link in the same change, and update any
  adapter symlink that pointed at the old name.
- Do not delete a rule because it is inconvenient for the change you are making. Rules change by
  agreement, in their own pull request.

## Adding a skill

A skill is `.ai/skills/<kebab-case-name>/SKILL.md` and starts with valid YAML frontmatter carrying
`name` and `description`:

```yaml
---
name: kotlin-framework-development
description: Use when implementing or changing framework behavior in Kotlin, to follow the module boundaries, gates, and API baseline workflow.
---
```

- `name` is kebab-case and matches the directory name exactly.
- `description` is one sentence that starts with **when** to use the skill, so an agent can route to
  it without opening the file.
- The body is an ordered workflow with the actual commands to run and the gates that prove the work,
  not a restatement of principles.

## Verify the corpus after any change

Every relative link and every symlink must resolve. One command checks all of it:

```bash
./.ai/verify-corpus.sh
```

It verifies that every adapter is a real symlink resolving into `.ai/`, that no adapter path holds a
duplicate copy, that every skill has valid frontmatter whose `name` matches its directory, that every
relative link inside `.ai/` resolves, that `general.md` indexes every rule and skill, that no work
record was filed here, and that markdownlint is clean. `./Documentation/verify-markdown.sh` covers
`Documentation/**` only and does not look at `.ai/` at all, so this is the gate for corpus changes.

The linter step is the equivalent of:

```bash
npx markdownlint-cli2 ".ai/**/*.md"
```

The repository root `.markdownlint-cli2.jsonc` disables only `MD013`; all other default rules apply,
so keep ATX headings with blank lines around them, blank lines around lists and fences, a language on
every fence, and a single trailing newline. Unlike `Documentation/` pages, `.ai/` files use an `# H1`
as the first line and carry no YAML frontmatter.

## Facts must be verified against this repository

Every factual claim in this corpus — a Gradle task, an annotation, a KSP diagnostic, a module
boundary, a workflow name — must be checked against this repository's own code, build files, tests,
workflows, or documentation before it is written. Do not infer from a name, do not carry over a
convention from a Cratis .NET repository, and do not invent an identifier. If something cannot be
verified, leave it out or mark it explicitly as unverified.

State which of the three authority levels a rule carries when it matters: **framework contract**
(compiler, KSP, `apiCheck`, or runtime enforces it), **repository convention** (the house default, not
machine-enforced), or **product policy** (belongs in a consuming application, not here). Never write
"the framework requires" for a convention.

## Do not copy this corpus into another repository

Shared Cratis AI behavior is authored and approved in `Cratis/AI` and distributed from there. Do not
copy `.ai/`, `.claude/`, `.agents/`, or `.github/` trees from this repository into another one, and do
not import another repository's rules wholesale into this one — Chronicle and .NET corpora carry C#,
React, EF Core, and Orleans material that does not belong here.

If you find an improvement here that should apply organization-wide, take it upstream as an issue or
pull request against `Cratis/AI`.

## This corpus is repository-local

`.ai/` in Arc.Kotlin is repository-local, tracked repository configuration. It is **not** an installed
shared distribution package, and nothing regenerates or overwrites it from elsewhere. Changes are made
here, reviewed here, and merged here under
[git-and-pull-requests.md](./git-and-pull-requests.md). Corpus files are tracked content, not work
records — see [local-work-artifacts.md](./local-work-artifacts.md) for what must stay out of git.
