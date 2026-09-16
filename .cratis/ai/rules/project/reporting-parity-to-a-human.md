---
applyTo: "**/*"
---

## Reporting parity to a human

Say what ran, what passed, and what you did not check. "Matches Arc .NET" is not a report. Acceptable
shapes are: "`:ContractTests:typeScriptRuntimeTest` passes with the exact TAP totals; the .NET
differential still compares against the normalized fixture, so raw-output equivalence is unproven",
or "the behavior is implemented and covered by `ArcQueryHostingTests`; I did not verify the Arc .NET
side and make no parity claim." When in doubt, understate — see the verification discipline in
[general.md](./general.md) and the no-placeholder rule in [framework.md](./framework.md).

---

# Git and pull requests

This rule governs how work reaches `main` in Arc.Kotlin: branching, commit message style, what a pull
request must carry before it can merge, how a release is actually cut, and — most importantly — how a
pull request is merged. The merge policy and the history policy below are absolute: they are not
defaults to weigh against convenience.
