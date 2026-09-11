---
name: documentation-authoring
description: Use when writing or changing a page under `Documentation/` — choosing the section, adding the frontmatter, registering the page in the nearest `toc.yml`, keeping snippets inside what `validate-doc-snippets.py` enforces, and running `./Documentation/verify-markdown.sh`.
---

# Documentation authoring

Invariants — when a change must update documentation, the section map, the house voice, and the
exact snippet contract — live in [documentation.md](../../rules/documentation.md). This skill is the
procedure for producing a page that passes the gate on the first run. It applies to
`Documentation/**/*.md` only; `README.md`, `AGENTS.md`, module READMEs, and the `.ai/` corpus are not
linted by this gate.

The gate needs `npx` (Node.js) and `python3` on the PATH. It does not need a JDK or Gradle.

## 1. Decide whether a page changes at all

Ask whether a reader following the existing page would now be wrong. If yes, the page is part of the
change and ships in the same commit, not as a follow-up. Internal refactoring that moves no
consumer-visible contract needs no documentation change.

## 2. Pick the section

| Section | Contains |
| --- | --- |
| `Documentation/get-started/` | Runnable end-to-end tutorials, one per consumer language (`index.md` is Kotlin, `java.md` is Java) |
| `Documentation/guides/` | One focused capability each — `commands.md`, `queries.md`, `spring-data.md`, `typescript-proxies.md`, `openapi.md`, `observability.md`, `chronicle.md`, `testing.md` |
| `Documentation/reference/` | Exact contracts and honest status — `annotations.md`, `configuration.md`, `http-contract.md`, `parity.md` |

`Documentation/index.md` is the landing page (positioning, module map, current status). Do not add a
fourth top-level section without agreement; every section is a `toc.yml` the root `toc.yml` must
reference.

## 3. Create or edit the page

1. Open the file with YAML frontmatter carrying exactly `title` and `description`, then start the
   body at `##`. No page uses an `#` H1 — the frontmatter `title` is what satisfies markdownlint's
   `MD041`.

   ```markdown
   ---
   title: Create and validate commands
   description: Define model-bound Arc commands, resolve Spring services, validate requests, and use asynchronous handlers.
   ---

   ## Define a command
   ```

2. Keep `description` to one sentence saying what the reader will be able to do.
3. Write in American English (behavior, serialize, initialize, color) and imperatively to the reader.
4. Document actual behavior and status only. State a boundary in the same sentence as the capability
   the way the existing pages do, rather than moving caveats to a footnote.
5. Do not upgrade a row in `Documentation/reference/parity.md` or delete a stated limitation without
   a test, contract test, or runnable sample that proves it — see [arc-parity.md](../../rules/arc-parity.md).
6. Say "the local workspace version is `0.0.0-SNAPSHOT`" rather than implying a published artifact
   that does not exist yet.

## 4. Register the page in the nearest `toc.yml`

A new page is invisible until it is listed, and the gate fails on an unresolvable `href`.

1. Add a `name`/`href` pair to the `toc.yml` in the same directory, with the path relative to that
   `toc.yml`:

   ```yaml
   - name: Guides
     href: index.md
     items:
       - name: Commands
         href: commands.md
   ```

2. A brand-new section needs its own `toc.yml` plus an entry in `Documentation/toc.yml`, where the
   `href` points at the child `toc.yml` (`get-started/toc.yml`, `guides/toc.yml`, `reference/toc.yml`).
3. Never leave a `toc.yml` empty — the check fails if it finds zero `href` values across all of them.

## 5. Keep snippets inside what the validator enforces

`Documentation/validate-doc-snippets.py` is a source-contract check, not a compiler. It concatenates
every `*.md` under `Documentation/`, every `.kt`/`.java` under `Source`, `Integrations`, `Testing`,
and `GradlePlugin`, plus seven required sample files, and enforces exactly four things:

1. **Seven cross-check files must exist**, or it exits before checking anything:
   `Samples/Kotlin/SpringBoot/src/main/kotlin/io/cratis/arc/samples/kotlin/springboot/CreateTask.kt`,
   the sibling `TaskView.kt`, the Java equivalents under
   `Samples/Java/SpringBoot/src/main/java/io/cratis/arc/samples/javaspringboot/`,
   `Samples/Kotlin/SpringBoot/src/test/kotlin/io/cratis/arc/samples/kotlin/springboot/KotlinSampleApplicationTests.kt`,
   `Samples/Java/SpringBoot/src/test/java/io/cratis/arc/samples/javaspringboot/JavaSampleApplicationTests.java`,
   and `GradlePlugin/src/main/kotlin/io/cratis/arc/gradle/ArcExtension.kt`.
2. **Every watched symbol you mention must exist in the framework source.** The watched set is
   exactly `Command`, `CommandKey`, `ReadModel`, `FromServices`, `AllowAnonymous`, `Authorize`,
   `Roles`, `TreatWarningsAsErrors`, `Path`, `QueryHttpMethod`, `QueryTransport`, `CommandValidator`,
   `CommandContext`, `ValidationResult`, `ValidationResultSeverity`, `CommandScenario`,
   `BlockingCommandScenario`. Each is matched as a whole word anywhere in the documentation, then as
   a plain substring of the concatenated sources. Naming a renamed or not-yet-existing type from that
   list fails the gate — including in prose, not just in a fence.
3. **Four literals must stay present** both in the documentation and in the sample corpus (the seven
   files above plus `GradlePlugin/README.md`): `/api/create-task`, `/api/tasks`, `"arguments":{}`,
   and `generateArcProxies`. Removing the last occurrence of any of them from `Documentation/` breaks
   the build, so leave the tutorial routes and the QUERY body example intact when you edit.
4. **At least one fenced `kotlin` or `java` block must exist** across all of `Documentation/`.

It does not compile, run, or type-check anything. A snippet that must be correct belongs in a sample
first and is then quoted into the page.

## 6. Fences, links, and lint

- Give every fence a language. Already in use: `kotlin`, `java`, `bash`, `shell`, `json`, `yaml`,
  `properties`, `typescript`, `markdown`, `mermaid`. Fenced blocks only — no indented code blocks.
- Links between pages are relative and must resolve from the file containing them: `commands.md` for
  a sibling, `../guides/commands.md` from `get-started/`, `reference/parity.md` from
  `Documentation/index.md`. Prefer linking to another documentation page over a source path.
- Lint runs `npx markdownlint-cli2 "Documentation/**/*.md"` against the root
  `.markdownlint-cli2.jsonc`, which disables only `MD013` (line length). Everything else applies:
  ATX headings incrementing by one and surrounded by blank lines, blank lines around lists and
  fences, `-` as the single unordered marker, no duplicate heading text in a page, no multiple
  consecutive blank lines, no trailing spaces, no bare URLs, one trailing newline.
- Line length is unconstrained, but wrap prose the way the file you are editing already does.

## 7. Verify locally, and do not over-verify

```bash
./Documentation/verify-markdown.sh
```

It runs from the repository root and performs four checks in order — markdownlint, the snippet
validator, `toc.yml` href resolution, and `linkinator` (which also fails if it scans zero links). It
prints each exit code before failing, so read the summary line to see which check broke.

A documentation-only change must not be blocked on the expensive runtime gates it cannot affect: the
full Gradle workspace build, `verifyContractTestProxyDeterminism`, `typeScriptBuild`, and
`typeScriptRuntimeTest`. Run `verify-markdown.sh`, let CI run the rest, and do not re-run Gradle to
"confirm" a Markdown edit.

Two release consequences to remember when you ship it: `publish.yml` has no `Documentation/**` path
filter, so merging a docs-only change triggers no publish run — and the release-intent gate still
requires a label. See [ship-changes](../ship-changes/SKILL.md) for which one.

## Verify

```bash
./Documentation/verify-markdown.sh
```

Done means: the page carries `title` and `description` frontmatter and opens at `##`, it is listed in
the nearest `toc.yml`, every claim on it describes behavior that exists today, and
`verify-markdown.sh` printed `All documentation checks passed.` Report that line as the evidence, and
name anything you did not verify.
