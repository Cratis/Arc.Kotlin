---
applyTo: "**/*"
---

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
