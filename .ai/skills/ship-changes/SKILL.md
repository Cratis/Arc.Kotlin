---
name: ship-changes
description: Use when asked to commit, push, open a pull request, ship, or land a change in Arc.Kotlin; stop at the requested endpoint and separately authorize exact external effects.
---

# Ship changes

Invariants for commits, branches, and merge policy live in
[git-and-pull-requests.md](../../rules/git-and-pull-requests.md); the `.ai-work/` policy lives in
[local-work-artifacts.md](../../rules/local-work-artifacts.md). This skill is the procedure.

**This workflow performs no destructive git operation.** It never force-pushes, never rewrites
history, never squashes, never rebases, never amends a commit, and
never deletes a branch or a remote ref. If a situation seems to call for one of those, stop and ask a
human, and propose an additive alternative (a follow-up commit, or `git revert`).

## Authorization and stopping points

The requested verb is the stopping point, not permission for the whole workflow:

- **Commit-only:** review, explicitly stage authorized paths, commit, and stop. Do not push or open a PR.
- **Push-only:** push the authorized branch/commits and stop. Do not create additional commits or a PR unless requested.
- **PR-only:** prepare/open the requested PR and report required checks; stop before merge.
- **Ship/land:** clarify the exact intended endpoint and effects. These words alone do not authorize destructive or notification-bearing effects.

Merge, issue comments or closure, label mutations (especially labels that trigger publication/releases), publication, and local or remote branch deletion each require separate explicit authorization for exact targets and effects. Apply the repository's current mutation protocol and stricter local/private gates; tool access and inverse escrow alone supply no authority. For destructive/bulk effects, prepare an exact dry-run, capture pre-state and deterministic inverse escrow in ignored `.ai-work/`, obtain approval, recheck preconditions, and record/read back outcomes through the approved repository-owned adapter. If a required adapter, safe inverse/compensation, or authorization is missing, stop. Preserve any stricter prohibition below.

Never rewrite history: no amend, rebase, squash merge, hard reset, force-push, or forced branch deletion. Use new commits, revert, cherry-pick, and merge instead. A request to ship does not override this prohibition.

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
| Full workspace | `./gradlew build --no-configuration-cache -x :ContractTests:typeScriptRuntimeTest` | cross-cutting work or required merge/release gate; use affected-module incremental checks during development |
| Binary compatibility | `./gradlew apiCheck` | any published public API shape moved |
| Proxy determinism | `./gradlew :GradlePlugin:verifyContractTestProxyDeterminism :ContractTests:typeScriptBuild --no-configuration-cache` | generated proxies or the TypeScript surface moved |
| TypeScript runtime | `./gradlew :ContractTests:typeScriptRuntimeTest --no-configuration-cache` | the runtime proxy contract moved |
| Documentation | `./Documentation/verify-markdown.sh` | anything under `Documentation/` changed |

Documentation changes use the documentation gate; `.ai/` changes use `./.ai/verify-corpus.sh`, not Gradle. Wider required CI still applies — see
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
commit, pushed or not. Add only attribution trailers your environment actually requires; the
repository mandates none of its own.

## 6. Push

**Commit-only stops after step 5.** Push only when explicitly requested; push-only does not authorize new commits or a PR.

```bash
git push -u origin <branch>
```

Plain `git push` only. No `--force`, no `--force-with-lease`.

## 7. Open the pull request

**Push-only stops after step 6.** PR creation requires a request; PR-only stops before merge.

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

Prepare reviewer context separately from release notes. Posting it is a notification effect: require separate explicit authorization for the exact comment text and target, plus repository effect gates. Otherwise return it locally without posting. Only after authorization:

```bash
gh pr comment <number> --body-file <path>
```

That comment carries three things:

- **What changed** — the behavior and the internal work, not a file list.
- **Verified** — each gate you ran, named, with its result. Quote the command.
- **Not verified** — every gate you did not run, and why. This section is required and an empty one
  must say "none"; leaving it out is how a false green gets merged.

## 8. Apply the release-intent label

Documentation-only changes use repository-supported non-release intent, ordinarily `no-release`; confirm the workflow contract rather than assuming a label or API state. Run relevant content, link, frontmatter, and corpus checks instead of unrelated application builds, and satisfy every repository-required check, including release-intent checks where supported. Documentation is never a blanket exemption from red CI.

Propose exactly one supported release-intent label after inspecting current workflows. Applying any label, especially one that triggers publication, requires separate explicit authorization for its exact effects. Read back the result; unknown outcomes require reconciliation, not blind retries.

## 9. Watch CI and fix what it reports

```bash
gh pr checks <number> --watch
gh run view <run-id> --log-failed
```

Workflows that run on a pull request to `main`: **Kotlin Build** (`build.yml` — full workspace build,
proxy determinism and strict TypeScript, TypeScript runtime gate, documentation verification),
**CodeQL**, **Verify Semver Label**, **Verify No Work Records**, and **Chronicle Real Kernel** when
paths it watches changed. Fix what CI reports with new commits on the branch and push again. After a
fix, re-run the gate that failed rather than arguing to green. Diagnose unrelated/environmental failures within a bounded attempt and report blockers; never retry indefinitely or bypass required red CI.

## 10. Merge with a real merge commit

Confirm current merge settings read-only; do not rely on a recorded API sample.
**PR-only stops before this step.** Require separate explicit authorization for
the exact PR/head, merge, and declared publication effects, and passing required
checks. Only then use a true merge commit:

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

History rewriting remains prohibited; propose additive recovery, not approval to rewrite. This skill never deletes branches/refs. Publication, tagging, cleanup, and repository/settings changes are separate exact operations outside the requested commit/push/PR endpoint and need their own explicit authorization and controlling policy.

## Verify

```bash
git status                       # clean tree, nothing ignored staged
git log --oneline -3             # your commits, on your branch, not on main
gh pr view <number>              # only if a PR was authorized: reviewed body and supported intent
gh pr checks <number>            # only for a PR: required checks pass; failures are blockers
```

Done means the requested endpoint was reached with authorized scope and truthful verification. Commit-only stops after commit; push-only after push; PR-only before merge. Report pending effects and blockers without executing them. A merge, when separately authorized for the exact PR/head and effects, uses `gh pr merge --merge`.
