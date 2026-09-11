---
applyTo: "**/*"
---

## Never rewrite published history

- No `git push --force`, `-f`, or `--force-with-lease` on any pushed branch.
- No `git commit --amend`, `git rebase`, interactive rebase, `git reset` that drops commits, or
  `git filter-branch` on commits that have been pushed.
- Prefer additive history: add a follow-up commit instead of amending, `git revert` to undo,
  `git cherry-pick` to move a commit, and `git merge` instead of `git rebase` to take in `main`.
- If a situation seems to call for a force-push or any history rewrite, stop and ask first, and
  propose a non-destructive alternative.
