---
title: KSP diagnostics
description: Every compile-time diagnostic Arc's annotation processor can report, what it means, and whether it stops the build.
---

Arc discovers your commands, read models and validation rules while the JVM
compiler runs, through a KSP annotation processor. When it cannot make sense of a
declaration it reports a diagnostic against that declaration rather than failing
later at runtime or silently generating nothing.

Every diagnostic carries a stable code. **Errors stop code generation**, so the
build fails and no artifact manifest is written. **Warnings identify a convention
that compiles but is probably not what you meant** — most often a declaration Arc
will ignore, which shows up later as a missing endpoint or a proxy that was never
generated.

Codes are grouped by the artifact they concern: `01xx` commands, `02xx` queries
and read models, `03xx` proxy generation and validation, `04xx` interoperability.

The C# implementation reports equivalent conventions through Roslyn analyzers
with `ARC` codes; see [code analysis](/arc/backend/csharp/code-analysis/). The two
sets are not numbered alike, because each follows what its own compiler can see.

## Diagnostics

| Code | Severity | Description |
| --- | --- | --- |
| `ARCKSP0001` | Error | Invalid KSP configuration |
| `ARCKSP0100` | Warning | Command-like type is missing @Command |
| `ARCKSP0101` | Error | Unsupported command declaration |
| `ARCKSP0102` | Error | Invalid command handle function |
| `ARCKSP0103` | Error | Invalid command provide function |
| `ARCKSP0104` | Error | Unsupported command method parameter |
| `ARCKSP0105` | Error | Unsupported command method return type |
| `ARCKSP0106` | Error | Ambiguous command key |
| `ARCKSP0107` | Warning | Provided value is not consumed by handle |
| `ARCKSP0108` | Error | Conflicting authorization metadata |
| `ARCKSP0109` | Error | Ambiguous command response values |
| `ARCKSP0110` | Error | Invalid command event metadata |
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
| `ARCKSP0305` | Error | Concrete polymorphic base used as a property type |
| `ARCKSP0306` | Error | Unsupported @ExportedType declaration target |
| `ARCKSP0307` | Error | Unsupported identity details provider declaration |
| `ARCKSP0308` | Error | Unsupported fluent validation declaration |
| `ARCKSP0309` | Error | Invalid or unrepresentable fluent validation rule |
| `ARCKSP0310` | Error | Missing or conflicting fluent validation compiler metadata |
| `ARCKSP0311` | Error | Unsupported or ambiguous validation ignore member |
| `ARCKSP0400` | Warning | Java/Kotlin interoperability hazard |
| `ARCKSP9999` | Error | Unclassified Arc KSP diagnostic |

## When a diagnostic fires

A diagnostic names the declaration that caused it. Fix the declaration rather
than suppressing the code: Arc only generates artifacts it can fully describe, so
a suppressed error means the endpoint, proxy or validation rule you expected will
not exist.

If you hit `ARCKSP0001` the processor itself is misconfigured rather than your
code being wrong — check that the `io.cratis.arc` Gradle plugin is applied and
that KSP is on the compile path, as described in
[getting started](../get-started/index.md).
