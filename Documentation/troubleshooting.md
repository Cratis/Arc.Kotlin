---
title: Troubleshooting and FAQ
description: Common Arc.Kotlin errors and questions, with the fix and where the full contract is documented.
---

## Build and Gradle

### Gradle can't find a JDK 17

Arc's Gradle plugin and generated code target JDK 17 exactly. Make a JDK 17 installation the active
`JAVA_HOME`/`PATH` before running `./gradlew`:

```shell
export JAVA_HOME=/opt/homebrew/opt/openjdk@17   # path varies by OS and installer
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

The sample [`run.sh` scripts](https://github.com/Cratis/Arc.Kotlin/blob/main/README.md#running-the-samples) do this detection automatically.

### `generateArcProxies` fails with "Unsupported Arc artifact manifest format N; expected M"

A classpath mixes manifests produced by different Arc KSP versions. Rebuild **every** module that
contributes an Arc manifest — both the producer (the module declaring `@Command`/`@ReadModel` types)
and every consumer (the module calling `generateArcProxies` against those types) — with the same Arc
version. See [Configuration reference](reference/configuration.mdx) for the manifest format and what
changes between versions.

### Startup fails with "Arc endpoint-options mismatch"

The Gradle plugin's build-time route settings (`cratisArc.endpoints.*`) disagree with the runtime
properties (`cratis.arc.endpoints.*`). The error names the exact setting and both values — align
them in `build.gradle.kts` and `application.properties`. See
[Configure generation](guides/typescript-proxies.md#configure-generation).

### `./gradlew checkSpringBoundary` fails

`Source`'s compiled `artifacts`, `metadata`, and `json` packages — and every local type they
transitively reference — must stay Spring-free. This check exists to keep the KSP processor and
Gradle plugin usable without pulling Spring onto the compiler/build-tool classpath. Move the
offending dependency behind an integration module instead of adding it to `Source`.

## Compile-time (`ARCKSP`) errors

Every KSP diagnostic carries a stable code and an actionable message, for example:

```text
[ARCKSP0101] Command 'CreateTask' must be a public top-level class.
```

The complete catalog, with severity and meaning, is in `CodeGeneration/KSP/DIAGNOSTICS.md` at the
repository root. The most common ones:

| Code | Usual cause |
| --- | --- |
| `ARCKSP0100` | A class looks command-shaped (has a public `handle`) but is missing `@Command` |
| `ARCKSP0101` / `ARCKSP0200` | A `@Command`/`@ReadModel` class is not a public top-level declaration |
| `ARCKSP0102` / `ARCKSP0201` | `handle` or a query method is not public, or is on a private/internal companion |
| `ARCKSP0106` | Two members both resolve as the command key |
| `ARCKSP0108` | `@AllowAnonymous` combined with `@Authorize`/`@Roles`, on the same target or across class and operation |
| `ARCKSP0109` | An aggregate command response leaves more than one possible client-visible leaf |
| `ARCKSP0300` | A computed or read-only Kotlin property reached through command input; use a backed property or a separate output model |
| `ARCKSP0301` | A Jakarta/Hibernate constraint cannot be represented as a client-side rule |

See [Annotation reference](reference/annotations.md) for the exact contract each annotation enforces.

## Runtime behavior

### A command fails with `reason: "rule"` and `reasonDetail: "commandKey"`

The command needs a Chronicle event response but has no usable `@CommandKey`. Declare one backed by
`String`, `UUID`, a number, or a concept wrapping one of those. See
[Return an event](guides/chronicle.md#return-an-event).

### An observable query returns HTTP 202 with no data

The query's source is a cold `Flow` or `Flow.Publisher` that has not produced a value yet. Either
back the query with a `MutableStateFlow`/`SubmissionPublisher` (which always has a current value), or
request `waitForFirstResult=true` on the HTTP snapshot route. See
[Consume an observable query](guides/queries.md#consume-an-observable-query).

### The Chronicle integration fails at startup with `Could not provision event store ... (authorized=false, ...)`

The connected kernel is older than 18.4.0. Kernels 18.3.1 and earlier omit the `IsAuthorized` field
from gRPC responses when a request *was* authorized, which a JVM proto3 client decodes as `false`.
Upgrade to Chronicle kernel 18.4.0 or newer — the pinned development image is
`cratis/chronicle:18.4.0-development`. See
[Add Chronicle optionally](guides/chronicle.md#add-chronicle-optionally).

### A generated TypeScript client rejects a request with a malformed-request error

Check for a reserved key (`__proto__`, `prototype`, `constructor`) in a map property, a QUERY body
field outside `arguments`/`paging`/`sorting`, or a client argument name that collides with another
after case folding — GET and QUERY match argument names case-insensitively. See the
[HTTP contract reference](reference/http-contract.md#unknown-fields).

### Which sample should I run to see a specific behavior?

- No external dependencies, plain commands and queries: `Samples/Kotlin/SpringBoot` or
  `Samples/Java/SpringBoot`.
- Chronicle events, concurrency, and tenant-scoped read models: `Samples/Kotlin/ChronicleSpringBoot`
  or `Samples/Java/ChronicleSpringBoot`.

Each has a `run.sh` — see [Running the samples](https://github.com/Cratis/Arc.Kotlin/blob/main/README.md#running-the-samples).

## "Is this feature really implemented?"

Every implementation claim in this documentation is backed by a test, contract test, or runnable
sample named in the [feature parity reference](reference/parity.md). If a guide describes a behavior
and you want to know exactly what proves it — or whether it matches Arc on .NET — that document is
the source of truth, not this page or the README.

## Still stuck?

Open a [GitHub issue](https://github.com/Cratis/Arc.Kotlin/issues) with the exact error message, the
Arc version, and a minimal reproduction. The [Cratis Discord](https://discord.gg/kt4AMpV8WV) is the
place for open-ended questions.
