# Documentation

This rule governs everything under `Documentation/` — when a change must update it, which section a
page belongs to, how a page is registered in the table of contents, the house voice, the code-snippet
constraints that `Documentation/validate-doc-snippets.py` actually enforces, markdownlint and link
expectations, and the single command that verifies all of it. It does not govern `README.md`,
`AGENTS.md`, module `README.md` files, or the `.ai/` corpus; those are not linted by the documentation
gate.

## When a change must update `Documentation/`

Update documentation in the same change — not a follow-up — when any of these move:

- A public annotation, its targets, or its behavior (`Documentation/reference/annotations.md`).
- A configuration property or default (`Documentation/reference/configuration.md`).
- An HTTP route, envelope, transport, or status contract (`Documentation/reference/http-contract.md`).
- Implementation status against Arc .NET (`Documentation/reference/parity.md`).
- A published module identity or what it contains (`Documentation/index.md` module map).
- Anything a consumer follows step by step in the tutorials or guides.

Internal refactoring that changes no consumer-visible contract does not need a documentation change.
When you are unsure, ask whether a reader following the page would now be wrong; if yes, the page is
part of the change.

## Where a page goes

| Section | Contains | Shape |
| --- | --- | --- |
| `Documentation/get-started/` | Runnable end-to-end tutorials, one per consumer language | Prerequisites, Gradle setup, model, run, call it |
| `Documentation/guides/` | Focused task recipes for one capability | "Do this task" with working Kotlin and Java snippets |
| `Documentation/reference/` | Exact contracts and honest status | Tables of annotations, properties, routes, parity rows |

`Documentation/index.md` is the landing page: positioning, module map, current status, and the three
entry points. Do not add a fourth top-level section without agreement — every section is a `toc.yml`
that the root `toc.yml` must reference.

## Page shape

Every page starts with YAML frontmatter carrying `title` and `description`, then opens at `##`. No
page in `Documentation/` uses an `#` H1, and none should — the frontmatter `title` is the page title
and satisfies markdownlint's `MD041`.

```markdown
---
title: Create and validate commands
description: Define model-bound Arc commands, resolve Spring services, validate requests, and use asynchronous handlers.
---

## Define a command
```

Keep `description` a single sentence that says what the reader will be able to do. Use American
English throughout (behavior, serialize, initialize, color).

## Register a new page in the nearest `toc.yml`

A new page is invisible until it is listed. Add a `name`/`href` entry to the `toc.yml` in the same
directory as the page, using a path relative to that `toc.yml`:

```yaml
- name: Guides
  href: index.md
  items:
    - name: Commands
      href: commands.md
```

The root `Documentation/toc.yml` links sections by pointing `href` at the child `toc.yml`
(`get-started/toc.yml`, `guides/toc.yml`, `reference/toc.yml`). A new section therefore needs its own
`toc.yml` plus an entry in the root one.

`verify-markdown.sh` resolves every `href:` value in every `toc.yml` under `Documentation/` against
that file's own directory and fails on the first target that is not a file. It also fails if it finds
zero `href` values at all, so never leave a `toc.yml` empty.

## House voice

- **Document actual behavior and status only.** Describe what this repository does today, not what it
  is expected to do.
- **Never claim Arc .NET parity that is not demonstrated.** `Documentation/reference/parity.md` is a
  deliberately hedged matrix with a status key (Implemented, JVM-specific, Partial, Not planned) and
  its rows name their remaining boundaries. Do not upgrade a row's status, and do not delete a stated
  limitation, without a test, contract test, or runnable sample that proves the change.
- **State boundaries in the same sentence as the capability.** The existing pages do this
  consistently; follow it rather than moving caveats to a footnote.
- Prefer a working snippet taken from the samples over prose.
- Write imperatively to the reader ("Annotate a Kotlin data class"), not about the framework's
  intentions.
- Say "the local workspace version is `0.0.0-SNAPSHOT`" rather than implying published availability
  that has not happened.

## Snippet rules

`Documentation/validate-doc-snippets.py` is a source-contract check, not a compiler. It concatenates
every `*.md` under `Documentation/`, every `.kt` and `.java` file under `Source`, `Integrations`,
`Testing`, and `GradlePlugin`, and the seven required sample files, then enforces exactly four things:

1. **Seven cross-check files must exist**, or the script exits immediately:
   - `Samples/Kotlin/SpringBoot/src/main/kotlin/io/cratis/arc/samples/kotlin/springboot/CreateTask.kt`
   - `Samples/Kotlin/SpringBoot/src/main/kotlin/io/cratis/arc/samples/kotlin/springboot/TaskView.kt`
   - `Samples/Java/SpringBoot/src/main/java/io/cratis/arc/samples/javaspringboot/CreateTask.java`
   - `Samples/Java/SpringBoot/src/main/java/io/cratis/arc/samples/javaspringboot/TaskView.java`
   - `Samples/Kotlin/SpringBoot/src/test/kotlin/io/cratis/arc/samples/kotlin/springboot/KotlinSampleApplicationTests.kt`
   - `Samples/Java/SpringBoot/src/test/java/io/cratis/arc/samples/javaspringboot/JavaSampleApplicationTests.java`
   - `GradlePlugin/src/main/kotlin/io/cratis/arc/gradle/ArcExtension.kt`
2. **A watched symbol mentioned anywhere in the documentation must exist in the framework source.**
   The watched set is exactly: `Command`, `CommandKey`, `ReadModel`, `FromServices`, `AllowAnonymous`,
   `Authorize`, `Roles`, `TreatWarningsAsErrors`, `Path`, `QueryHttpMethod`, `QueryTransport`,
   `CommandValidator`, `CommandContext`, `ValidationResult`, `ValidationResultSeverity`,
   `CommandScenario`, `BlockingCommandScenario`. Each is matched as a whole word in the documentation
   and then as a plain substring of the concatenated `Source`/`Integrations`/`Testing`/`GradlePlugin`
   sources. Documenting a renamed or not-yet-existing type from that list fails the gate.
3. **Four sample contract literals must appear in the documentation and in the cross-check corpus.**
   They are `/api/create-task`, `/api/tasks`, `"arguments":{}`, and `generateArcProxies`, and the
   second corpus is the seven sample files above plus `GradlePlugin/README.md`. Removing the last
   occurrence of any of them from `Documentation/` breaks the build, so keep the tutorials' routes and
   the QUERY body example intact when editing.
4. **At least one fenced `kotlin` or `java` block must exist** across all documentation. The script
   reports how many it counted.

What the script does **not** do: it does not compile, run, or type-check snippets. Runnable behavior
is proven by the Kotlin and Java sample tests, so a snippet that must be correct belongs in a sample
first and is then quoted into the page. Give every fence a language — `kotlin`, `java`, `bash`,
`shell`, `json`, `yaml`, `properties`, `typescript`, and `mermaid` are the languages already in use.

## Markdownlint expectations

`verify-markdown.sh` runs `npx markdownlint-cli2 "Documentation/**/*.md"` with the repository root
`.markdownlint-cli2.jsonc`, which disables only `MD013` (line length). Every other default rule
applies:

- ATX headings only, surrounded by blank lines, incrementing by one level.
- Blank lines around lists and around fenced blocks.
- A language on every fenced block, and fenced blocks only — no indented code blocks.
- One consistent unordered list marker (`-`), no multiple consecutive blank lines, no trailing spaces.
- No duplicate heading text within a page, and a single trailing newline at end of file.
- No bare URLs; wrap them in a link or backticks.

Line length is unconstrained, but the existing pages still wrap prose sensibly. Match the file you are
editing.

## Links

Links between documentation pages are relative and must resolve from the file that contains them:
`commands.md` for a sibling, `../guides/commands.md` from `get-started/`, `reference/parity.md` from
`Documentation/index.md`. `verify-markdown.sh` runs linkinator over `Documentation/**/*.md` in
`--markdown --recurse` mode and additionally fails if the checker reports scanning zero links, so a
broken relative link or an unreachable external URL fails the gate. Prefer linking to a documentation
page over linking to a source file path.

## Verify

```bash
./Documentation/verify-markdown.sh
```

It runs from the repository root and performs four checks — markdownlint, the snippet validator, the
`toc.yml` href resolution, and linkinator — reporting each exit code before it fails. It needs `npx`
(Node.js) and `python3` on the PATH; it does not need a JDK or Gradle.

Run it for any change under `Documentation/`. A documentation-only change should not be blocked on the
expensive runtime gates it cannot affect — the full Gradle workspace build, the proxy determinism
check, and the TypeScript runtime gate — so verify with `verify-markdown.sh`, let CI run the rest, and
do not re-run those gates locally to "confirm" an unrelated Markdown edit.

Note two release consequences of a documentation-only change: `publish.yml` does not list
`Documentation/**` in its path filter, so merging one triggers no Publish run, and the release-intent
gate still requires a label. See [git-and-pull-requests.md](./git-and-pull-requests.md) for which one.
