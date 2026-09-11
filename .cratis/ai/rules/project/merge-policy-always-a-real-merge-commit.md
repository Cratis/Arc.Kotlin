---
applyTo: "**/*"
---

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
