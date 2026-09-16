---
applyTo: "**/*"
---

## The runtime TAP gate and its exact totals

`:ContractTests:typeScriptRuntimeTest` runs `npm run test:runtime`
(`node contracts/run-runtime-gate.mjs`). It boots the executable Kotlin Spring Boot sample jar,
waits for `Tomcat started on port …`, then runs three child processes and parses each one's TAP
summary.

| Child run | Contract | Time zone | Expected tests |
| --- | --- | --- | --- |
| calendar runtime contract | `contracts/runtime.calendar.contract.ts` | `UTC` | 5 |
| calendar runtime contract | `contracts/runtime.calendar.contract.ts` | `America/Los_Angeles` | 5 |
| general runtime contract | `contracts/runtime.contract.ts` | `UTC` | 15 |

`enforceTapSummary` requires, per child run:

- `tests` exactly equal to the expected count, and `pass` exactly equal to the same count;
- `fail`, `cancelled`, `skipped`, and `todo` all exactly `0`;
- every one of those six summary fields present, appearing once, and numeric.

A missing field, a duplicated field, an extra test, or a single skipped test fails the gate. A Spring
process-spawn error fails cleanly rather than hanging. **Adding or removing a runtime test means
updating `expectedTests` in `run-runtime-gate.mjs` in the same change** — the gate is deliberately
exact so that a silently dropped test cannot pass.

`:ContractTests:typeScriptRuntimeHarnessTest` (`npm run test:runtime-harness`) runs five Node unit
tests over the harness itself, covering an exact successful summary, a skipped test, a missing field,
a count mismatch, and a spawn error. `typeScriptRuntimeTest` depends on it, so a broken harness
cannot mask a broken contract.
