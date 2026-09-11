---
applyTo: "**/*"
---

## Work records never enter git

Plans, handovers, session notes, continuation prompts, status boards, and scratch analyses are never
committed, on any branch. `.ai-work/` and `.pi/` are gitignored; never `git add -f` anything inside
them and never remove those ignore entries. `.github/workflows/verify-no-work-records.yml` enforces
this on every pull request and every push to `main`. Details are in
[local-work-artifacts.md](./local-work-artifacts.md).

---

# Testing

This rule governs where tests live, how they are named, which JUnit 5 idioms this repository
actually uses, what belongs in a module's own tests versus `ContractTests`, how the KSP
compile-testing fixtures are structured, and the test-support surface published as
`io.cratis:arc-testing`. It is the depth behind the `AGENTS.md` requirement to use JUnit 5 and to
verify important APIs from both Kotlin and Java.
