---
applyTo: "**/*"
---

## Editing `parity.md`

1. Land the behavior and its check first; the documentation change follows the evidence.
2. Edit only the rows your change affects, and keep the existing hedges of every other row byte for
   byte unless you are deliberately correcting one and can prove the correction.
3. Keep the "Current JVM contract" column describing what runs today, including the remaining
   boundary. A `Partial` row must state the boundary, not just the capability.
4. Reflect anything that changed for consumers in `README.md` (the "Current limits" section) and in
   the relevant `Documentation/reference/` page.
5. Run `./Documentation/verify-markdown.sh`.
6. In the pull request, name the test, contract test, or sample that justifies each status change,
   and name anything you did not verify.
