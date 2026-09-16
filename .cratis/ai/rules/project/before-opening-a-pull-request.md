---
applyTo: "**/*"
---

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
