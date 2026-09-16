---
applyTo: "**/*"
---

## Diagnostics

Every compile-time message carries a stable code, emitted as `[ARCKSPxxxx] message`. The catalog is
the `ArcDiagnostic` enum in `ArcDiagnostics.kt` and is the single source of truth.
`CodeGeneration/KSP/DIAGNOSTICS.md` is generated from it by `ArcDiagnostic.referenceMarkdown()` and
asserted byte-equal by `ArcDiagnosticReferenceTest`.

| Code | Severity | Meaning |
| --- | --- | --- |
| `ARCKSP0001` | Error | Invalid KSP configuration |
| `ARCKSP0100` | Warning | Command-like type is missing `@Command` |
| `ARCKSP0101` | Error | Unsupported command declaration |
| `ARCKSP0102` | Error | Invalid command handle function |
| `ARCKSP0103` | Error | Invalid command provide function |
| `ARCKSP0104` | Error | Unsupported command method parameter |
| `ARCKSP0105` | Error | Unsupported command method return type |
| `ARCKSP0106` | Error | Ambiguous command key |
| `ARCKSP0107` | Warning | Provided value is not consumed by handle |
| `ARCKSP0108` | Error | Conflicting authorization metadata |
| `ARCKSP0109` | Error | Ambiguous command response values |
| `ARCKSP0110` | Error | Invalid or ambiguous command event metadata |
| `ARCKSP0200` | Error | Unsupported read model declaration |
| `ARCKSP0201` | Error | Invalid query function |
| `ARCKSP0202` | Error | Ambiguous query overload |
| `ARCKSP0203` | Error | Unsupported query parameter |
| `ARCKSP0204` | Error | Unsupported query return type |
| `ARCKSP0205` | Error | Query transport and return type disagree |
| `ARCKSP0206` | Error | Ambiguous or duplicate query route |
| `ARCKSP0207` | Error | Duplicate fully qualified query name |
| `ARCKSP0208` | Error | Invalid query infrastructure parameter |
| `ARCKSP0209` | Error | Unsupported Kotlin query parameter default |
| `ARCKSP0210` | Error | Invalid host query adapter shape |
| `ARCKSP0300` | Error | Unsupported generated proxy model shape |
| `ARCKSP0301` | Error | Invalid or unrepresentable Jakarta validation metadata |
| `ARCKSP0302` | Error | Ambiguous or unprovable Arc enum wire value |
| `ARCKSP0303` | Error | Missing or blank @DerivedType identifier |
| `ARCKSP0304` | Error | Unsupported @DerivedType declaration target |
| `ARCKSP0400` | Warning | Java/Kotlin interoperability hazard |
| `ARCKSP9999` | Error | Unclassified Arc KSP diagnostic |

Rules for diagnostics:

- **Prefer a compile-time diagnostic over a runtime failure.** If generated code could not invoke a
  shape safely, or a generated proxy would be uncompilable, stop the compilation with a precise
  message rather than emitting something that fails later. `ARCKSP0301` exists precisely so that an
  unrepresentable client constraint fails the build instead of silently weakening browser-side
  validation.
- **Report through the explicit overload.** `ArcDiagnosticReporter` has a
  `classify(message)` fallback that maps message prefixes onto a code; it exists for legacy call
  sites. New reporting must pass the `ArcDiagnostic` explicitly:
  `logger.error(ArcDiagnostic.QUERY_RETURN, "…", node)`. Falling through to
  `ArcDiagnostic.INTERNAL` (`ARCKSP9999`) in a new rule is a bug.
- **Codes are append-only.** Never renumber, never reuse a retired code, never change a code's
  meaning. Consumers and tests match on the literal string.
- Always pass the `KSNode` so the message lands on the offending declaration.
- Messages describe the offending shape and the fix, in American English, ending with what to do.
