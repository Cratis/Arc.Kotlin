# Git and pull requests

This rule governs how work reaches `main` in Arc.Kotlin: branching, commit message style, what a pull
request must carry before it can merge, how a release is actually cut, and — most importantly — how a
pull request is merged. The merge policy and the history policy below are absolute: they are not
defaults to weigh against convenience.

## Branching

- **Never commit directly to `main`.** `main` is the default branch and the publish trigger. If you
  are on `main` and about to commit, create a branch first.
- Branch from an up-to-date `main`, one branch per pull request.
- Automation owns two observed prefixes in this repository: `dependabot/<ecosystem>/<dependency>` for
  Dependabot and `stagehand/work-<id>` for Cratis automation. Do not create branches under either
  prefix by hand.
- No human topic-branch convention is recorded in this repository's history yet, so this is a
  repository convention rather than an observed pattern: use a short, lowercase, hyphenated name,
  optionally prefixed with the change type — `fix/ksp-diagnostic-0109`, `docs/query-paging`,
  `feat/observable-hub-health`.

## Commit messages

Commits in this repository follow Conventional Commits, as `git log` shows:

```text
feat: establish Arc for Kotlin and Java
fix: align binary API baselines
fix: increase Kotlin daemon heap for CodeQL
build(deps-dev): bump eventsource in /ContractTests/TypeScript
```

- Use a `type:` or `type(scope):` prefix — `feat`, `fix`, `build`, `ci`, `docs`, `refactor`, `test`,
  `chore`.
- Subject in the imperative mood, lowercase after the prefix, no trailing period, ideally under 72
  characters.
- Use the body to explain **why**, and to name the gate or evidence that proved the change. Wrap the
  body at a readable width.
- One logical change per commit. Do not mix an unrelated `.api` baseline refresh, a dependency bump,
  and a behavior change in one commit.
- A deliberate public API change lands together with its regenerated `.api` baseline in the same
  commit, not as a later fix-up.
- Never commit build output, generated proxies, IDE files, or anything under `.ai-work/` or `.pi/` —
  see [local-work-artifacts.md](./local-work-artifacts.md).
- Do not commit, push, or open a pull request unless you were asked to.

## Before opening a pull request

Run the gates in the [general.md](./general.md) quality-gate table that your change can affect, and
`./Documentation/verify-markdown.sh` whenever anything under `Documentation/` changed. Push, then
watch CI: `Kotlin Build`, `Verify Semver Label`, `Verify No Work Records`, and CodeQL all run on pull
requests. The work is not done until CI is green, or the only failures are confirmed pre-existing and
unrelated.

The pull request **body is release-note copy**, and `cratis/release-action` generates the release
notes from it. Follow `.github/pull_request_template.md` exactly: an optional one-line `# Summary`,
then only the `##` sections that apply — `Added`, `Changed`, `Fixed`, `Removed`, `Security`,
`Deprecated` — in that order, with empty sections deleted. Each bullet is terse and user-facing and
ends with the real issue reference, for example `(#54)`. No extra sections, no prose, no verification
statistics, and no AI-attribution footer. A change with no user-facing effect contributes no bullet.

Reviewer context — what changed internally, which gates you ran with their results, and explicitly
what you did **not** verify — goes in a separate `gh pr comment`, never in the body.

## Release-intent label — required on every pull request

`.github/workflows/verify-semver-label.yml` calls the organization-wide reusable workflow
`Cratis/Workflows/.github/workflows/verify-release-intent.yml` on `opened`, `reopened`, `synchronize`,
`labeled`, and `unlabeled` for pull requests targeting `main`. It requires **exactly one** label from:

| Label | Meaning |
| --- | --- |
| `major` | Breaking release |
| `minor` | Backward-compatible feature release |
| `patch` | Backward-compatible fix release |
| `no-release` | The change ships nothing a consumer compiles against or runs |

The gate fails when there are zero of these labels, when two or more of `major`/`minor`/`patch` are
present, or when `no-release` is combined with any version label.

**Documentation-only changes are not exempt from labeling.** They are exempt from *versioning*: the
correct answer is `no-release`, which the workflow treats as a deliberate decision rather than an
omission. The same applies to CI, tooling, and spec-only changes. Dependabot pull requests in this
repository already carry `dependencies` and `no-release` via `.github/dependabot.yml`.

Never merge a pull request that has no release-intent label. `publish.yml` has a `verify-published`
job that fails after the fact when a merge published nothing and the merged pull request was not
labeled `no-release`.

## How a release is cut

- `publish.yml` runs on a push to `main` whose changes touch `Source/**`, `CodeGeneration/KSP/**`,
  `GradlePlugin/**`, the six `Integrations/**` modules, `Testing/**`, `build.gradle.kts`,
  `settings.gradle.kts`, or `gradle/**`. A merge that touches only `Documentation/**` or `.github/**`
  does not trigger it at all.
- It first re-verifies (`./gradlew clean build --no-configuration-cache` and
  `./Documentation/verify-markdown.sh`) plus the Chronicle real-kernel workflow, then `cratis/release-action`
  derives the version from the merged pull request's release-intent label, creates the GitHub release,
  and Gradle publishes the signed artifacts to Maven Central.
- **Serialize every merge that triggers `publish.yml`.** Before merging a pull request that touches
  any path listed above, confirm the latest `publish.yml` run is completed. After merging, wait for
  the resulting publish run to complete before merging another pull request that touches those paths.
  This applies even to `no-release`: its verification run occupies the same concurrency group.
  GitHub Actions keeps only one pending run per concurrency group even with
  `cancel-in-progress: false`; a newer pending run cancelled an older version-labelled run in #133.
  Do not treat the concurrency setting as a FIFO queue.
- A release can also be cut manually with `workflow_dispatch`, supplying an explicit version and
  release notes.
- Do not hand-edit versions: the local checkout builds as `0.0.0-SNAPSHOT` unless Gradle receives
  `-Pversion`.

## Merge policy — always a real merge commit

**Merge every pull request with `gh pr merge --merge`.** Never `--squash`, never `--rebase`, and never
the *Squash and merge* or *Rebase and merge* buttons.

A squash merge replaces the branch's commits with one new commit, and the branch is normally deleted
immediately afterwards, so nothing points at the originals any more. The commits on a branch are the
record of how the work was actually done, including the false starts; a tidier `main` is not worth
destroying that. A rebase merge replays the commits as new ones and has the same effect on the
original objects. Treat both as history rewrites even though they look like integration steps.

The repository currently permits all three merge methods
(`allow_merge_commit: true`, `allow_squash_merge: true`, `allow_rebase_merge: true`, default branch
`main`). That squash is *permitted* is not permission to use it. If the settings ever stop permitting
merge commits, **stop and ask a human** — that is a repository setting to change, not a reason to
squash.

The merges already on `main` are real merge commits (`Merge pull request #5 from ...`); keep it that
way.

## Never rewrite published history

- No `git push --force`, `-f`, or `--force-with-lease` on any pushed branch.
- No `git commit --amend`, `git rebase`, interactive rebase, `git reset` that drops commits, or
  `git filter-branch` on commits that have been pushed.
- Prefer additive history: add a follow-up commit instead of amending, `git revert` to undo,
  `git cherry-pick` to move a commit, and `git merge` instead of `git rebase` to take in `main`.
- If a situation seems to call for a force-push or any history rewrite, stop and ask first, and
  propose a non-destructive alternative.

## Work records never enter git

Plans, handovers, session notes, continuation prompts, status boards, and scratch analyses are never
committed, on any branch. `.ai-work/` and `.pi/` are gitignored; never `git add -f` anything inside
them and never remove those ignore entries. `.github/workflows/verify-no-work-records.yml` enforces
this on every pull request and every push to `main`. Details are in
[local-work-artifacts.md](./local-work-artifacts.md).
