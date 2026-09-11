---
applyTo: "**/*"
---

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
