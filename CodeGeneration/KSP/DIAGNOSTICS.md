# Arc KSP diagnostics

Arc KSP prefixes every compile-time diagnostic with a stable code. Errors stop code generation; warnings identify risky but compilable conventions.

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
| `ARCKSP0400` | Warning | Java/Kotlin interoperability hazard |
| `ARCKSP9999` | Error | Unclassified Arc KSP diagnostic |
