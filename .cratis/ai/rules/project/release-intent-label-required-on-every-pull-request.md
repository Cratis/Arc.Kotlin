---
applyTo: "**/*"
---

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
