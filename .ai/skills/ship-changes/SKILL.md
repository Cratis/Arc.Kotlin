---
name: ship-changes
description: Use when asked to commit, push, open a pull request, ship, or land a change in Arc.Kotlin — confirming what actually changed and which gates actually passed, branching off `main`, writing the commit, opening the PR with an honest verification section, applying the required release-intent label, watching CI, and merging with a real merge commit.
---

# Ship changes

Invariants for commits, branches, and merge policy live in
[git-and-pull-requests.md](../../rules/git-and-pull-requests.md); the `.ai-work/` policy lives in
[local-work-artifacts.md](../../rules/local-work-artifacts.md). This skill is the procedure.

**This workflow performs no destructive git operation.** It never force-pushes, never rewrites
published history, never squashes, never rebases a pushed branch, never amends a pushed commit, and
never deletes a branch or a remote ref. If a situation seems to call for one of those, stop and ask a
human, and propose an additive alternative (a follow-up commit, or `git revert`).

## 1. Confirm what actually changed

```bash
git status
git diff
git diff --staged
git log --oneline -5
```

Read the diff. Do not ship a change you have not looked at. If the working tree contains edits you
did not make in this task, stop and ask before staging anything.

## 2. Confirm nothing local or generated is being staged

```bash
git status --porcelain
git ls-files --others --exclude-standard
```

Nothing under `.ai-work/` or `.pi/` may ever be tracked — they are gitignored and
`verify-no-work-records.yml` fails the pull request if anything under `.ai-work/` is tracked. That
workflow also rejects work-record-style root documents: a root-level SCREAMING-CASE `*.md` outside
its allowlist (`README`, `LICENSE`, `AGENTS`, `CLAUDE`, `CONTRIBUTING`, `SECURITY`, `CHANGELOG`, and
similar), and any `*HANDOVER*.md`, `PROMPT-*.md`, `*NEXT-SESSION*.md`, or `SESSION-PROMPT*.md`
outside `.ai/`, `.claude/`, `.github/`, `.pi/`, `.agents/`. Never `git add -f` an ignored path. A
follow-up that must outlive the session becomes a GitHub issue, not a file.

Also check that no build output (`**/build/`, `ContractTests/TypeScript/generated/`,
`ContractTests/TypeScript/node_modules/`) crept in.

## 3. Confirm the gates were actually run

Name the commands you ran and the result you saw. **Never claim green from self-assessment** — a
gate you did not run is a gate you report as not run.

| Gate | Command | Run it when |
| --- | --- | --- |
| Full workspace | `./gradlew clean build --no-configuration-cache -x :ContractTests:typeScriptRuntimeTest` | any Kotlin/Java/Gradle change |
| Binary compatibility | `./gradlew apiCheck` | any published public API shape moved |
| Proxy determinism | `./gradlew :GradlePlugin:verifyContractTestProxyDeterminism :ContractTests:typeScriptBuild --no-configuration-cache` | generated proxies or the TypeScript surface moved |
| TypeScript runtime | `./gradlew :ContractTests:typeScriptRuntimeTest --no-configuration-cache` | the runtime proxy contract moved |
| Documentation | `./Documentation/verify-markdown.sh` | anything under `Documentation/` changed |

A documentation-only change runs only the documentation gate — see
[documentation-authoring](../documentation-authoring/SKILL.md). Gradle needs JDK 17 on the PATH;
check with `java -version` and export `JAVA_HOME` for your own JDK 17 install if it is missing. Never
commit a machine-specific JDK path.

## 4. Branch off the default branch

The default branch is `main`. Never commit directly to it.

```bash
git branch --show-current
git switch main && git pull --ff-only
git switch -c <type>/<short-kebab-description>
```

If you already committed on `main` by accident, stop and ask — do not reset or rewrite. The additive
recovery is to branch from the current commit and let a human decide what `main` should point at.

## 5. Commit

Stage deliberately (`git add <path>` per file, not `git add -A`), then write a message in the style
the history shows. The history is short (6 commits at the time of writing), and it is Conventional
Commits: `feat:`, `fix:`, `build(deps-dev):` with an imperative, lowercase subject and no trailing
period, and a body explaining why when the subject is not self-evident.

```bash
git add Source/src/main/kotlin/io/cratis/arc/queries/QueryPipeline.kt
git commit
```

```text
fix: propagate tenant namespace through the query pipeline

The namespace defaulted to the tenant ID only in the Spring Boot host, so
in-process scenarios saw a null namespace. Resolve it in the pipeline instead
and cover it from Kotlin and Java.
```

Keep one logical change per commit. Prefer several additive commits over amending — never amend a
commit that has been pushed. Add only attribution trailers your environment actually requires; the
repository mandates none of its own.

## 6. Push

```bash
git push -u origin <branch>
```

Plain `git push` only. No `--force`, no `--force-with-lease`.

## 7. Open the pull request

```bash
gh pr create --base main --title "fix: propagate tenant namespace through the query pipeline" --body-file <path>
```

### The body is release notes, and nothing else

`.github/pull_request_template.md` is the contract for the body, because `cratis/release-action`
generates the release notes from it. Follow it exactly:

- An optional one-line `# Summary`. Omit it when the bullets already tell the story.
- Only the `##` sections that apply, in the template's order: `Added`, `Changed`, `Fixed`,
  `Removed`, `Security`, `Deprecated`. Delete every section that has no bullet.
- Each bullet is terse and user-facing — a new API, changed behavior, a fixed bug — and ends with
  the real issue reference, for example `(#54)`. Never a placeholder, never an implementation detail.
- Nothing else. No extra sections, no bold sub-headings, no prose, no verification or test
  statistics, no AI-attribution footer, no prompt transcript.
- A change with no user-facing effect contributes no bullet at all.

### Reviewer context goes in a comment, not the body

Everything a reviewer needs but a release note must not carry goes in a separate comment posted
immediately after the pull request is created:

```bash
gh pr comment <number> --body-file <path>
```

That comment carries three things:

- **What changed** — the behavior and the internal work, not a file list.
- **Verified** — each gate you ran, named, with its result. Quote the command.
- **Not verified** — every gate you did not run, and why. This section is required and an empty one
  must say "none"; leaving it out is how a false green gets merged.

## 8. Apply the release-intent label

`.github/workflows/verify-semver-label.yml` calls
`Cratis/Workflows/.github/workflows/verify-release-intent.yml`, which requires **exactly one** of
`major`, `minor`, `patch`, or `no-release`. Zero labels fail, two version labels fail, and a version
label together with `no-release` fails. All four labels exist in this repository.

Documentation-only changes are **not** exempt from the gate, but they take no semantic version label:
`no-release` is the correct answer for a change that alters nothing a consumer compiles against or
runs — documentation, CI, tooling, and spec-only changes. That is also consistent with `publish.yml`,
whose path filter does not include `Documentation/**`.

```bash
gh pr edit <number> --add-label patch      # or major / minor
gh pr edit <number> --add-label no-release # documentation, CI, or tooling only
```

## 9. Watch CI and fix what it reports

```bash
gh pr checks <number> --watch
gh run view <run-id> --log-failed
```

Workflows that run on a pull request to `main`: **Kotlin Build** (`build.yml` — full workspace build,
proxy determinism and strict TypeScript, TypeScript runtime gate, documentation verification),
**CodeQL**, **Verify Semver Label**, **Verify No Work Records**, and **Chronicle Real Kernel** when
paths it watches changed. Fix what CI reports with new commits on the branch and push again. After a
fix, re-run the gate that failed rather than arguing to green. The task is not done until CI is green
or the only failures are confirmed pre-existing and unrelated — say so explicitly if they are.

## 10. Merge with a real merge commit

Repository merge settings, checked with:

```bash
gh api repos/Cratis/Arc.Kotlin --jq '{merge:.allow_merge_commit,squash:.allow_squash_merge,rebase:.allow_rebase_merge,default:.default_branch}'
# {"default":"main","merge":true,"rebase":true,"squash":true}
```

Merge commits are allowed, so merge exactly this way:

```bash
gh pr merge <number> --merge
```

**Never `--squash` and never `--rebase`**, and never the "Squash and merge" or "Rebase and merge"
buttons. Both replace the branch's commits with new ones, and the branch is normally deleted right
afterwards, so nothing points at the originals any more — the record of how the work was actually
done is destroyed. A squash merge is a history rewrite even though it looks like an integration step.
If a repository's settings ever stop allowing a merge commit, stop and ask a human to change the
setting; that is not a reason to squash.

## Stop and ask before anything irreversible

Ask a human first, every time, for: a force-push of any kind, a squash or rebase merge, rewriting or
amending pushed history, `git reset --hard` over work you did not create, deleting a branch or tag,
`git clean` over untracked files, publishing or tagging a release, and changing repository settings
or branch protection.

## Verify

```bash
git status                       # clean tree, nothing ignored staged
git log --oneline -3             # your commits, on your branch, not on main
gh pr view <number>              # title, body with Verified / Not verified, one release-intent label
gh pr checks <number>            # all green, or failures named as pre-existing
```

Done means: the branch is not `main`, no work record or build output is tracked, every gate you
claimed is one you ran, the pull request states what you did not verify, exactly one of
`major`/`minor`/`patch`/`no-release` is applied, CI is green, and the merge — if you were asked to
land it — used `gh pr merge --merge`.
