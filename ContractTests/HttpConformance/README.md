# Selected cross-runtime HTTP conformance

An explicit HTTP test runs three real hosts in separate processes:

- A repository-authored ASP.NET Core task board against published `Cratis.Arc` **22.14.0**.
- The real KSP-generated Kotlin Spring Boot task-board sample.
- The real KSP-generated ordinary-Java Spring Boot task-board sample.

The .NET fixture uses public Arc startup and model-bound discovery, not handwritten endpoint/envelope
substitutes. It has an in-memory task store and no Chronicle, MongoDB or external database. Its binaries
come only from NuGet. Nothing is read or built from the sibling Arc checkout. JVM samples consume the
local framework source and their existing public starter/configuration.

## Explicit run

Install JDK 17 and .NET SDK **10.0.400**, with both Microsoft.NETCore.App and Microsoft.AspNetCore.App
**10.0.11**. The SDK/source configuration is shared with the proxy-capture harness; the HTTP Web project
has its **own** 71-package lock and archive checksums because framework-provided dependencies are pruned.
Normal runs use locked restore and verify exact graph membership, package archives and cached payloads.
Changing these pins requires a separate reviewed restore, not a silent update in a test run.

From the physical repository root, with `JAVA_HOME`/`PATH` set:

```bash
ai-work-lifecycle --repo "$PWD" run-task paired-http -- sh -c \
  './gradlew :ContractTests:httpConformanceTest --no-configuration-cache \
    -Parc.httpConformance.output="$AI_WORK_KEEP/http-proof"'
```

Choose a new task name for each run. The task builds the two sample boot JARs, runs the Python harness
unit tests, restores/publishes the pinned .NET fixture, then executes the hosts sequentially. It is
**not attached to ordinary `check`, `build`, proxy capture or TypeScript generation**. Explicit runs
require Python 3.9+, the pinned runtimes and NuGet access/cache; missing prerequisites fail, not skip.

The runner verifies .NET's published runtime configuration and readiness-reported loaded framework
locations, checks Java 17, and records executable/sample-JAR/input hashes. Archive hashes are integrity
pins, not independent publisher authentication or execution attestation.

## Nine shared cases

Each host must pass every case; a combined result is published only after all three finish:

1. A fresh host returns an empty query array.
2. Two create commands return distinct UUID identifiers and exact typed title responses.
3. GET by identifier returns the requested one of two distinguishable tasks.
4. RFC QUERY by identifier returns the requested task, with `Cache-Control: no-store`.
5. Enumerable QUERY returns the complete two-task snapshot.
6. Successful command validation returns no handler response and leaves the complete snapshot unchanged.
7. Completion returns the exact updated task and changes only that task in the subsequent snapshot.
8. Malformed command JSON returns 400 with `malformedRequest` validation, no parser exception/stack,
   and leaves the complete snapshot unchanged.
9. An unknown route returns 404.

Successful command/query envelopes must report success/authorized/valid, no exceptions or validation
failures, and a valid correlation UUID. Boolean and numeric values are not interchangeable. Snapshots
are compared as complete task objects keyed by identifier: incidental array order is not asserted.
Raw response bodies, methods, paths, request fixtures, statuses and selected response headers are retained
unchanged in `results.json`, including fields outside the shared assertions.

## Deliberate limits

This is a selected task-board wire contract, **not full Arc API parity**. The Kotlin sample's creation
also returns a server-handled value; JVM completion uses the provide/revision lifecycle whereas the
.NET fixture performs a synchronous in-memory update. The suite proves the common HTTP result, not
those internal lifecycle mechanisms or concurrent access. Successful validate-route nonexecution is
not equivalent validation-rule discovery or rejection-policy coverage.

Do not conflate these observed differences with equality:

- The Kotlin list query has iterable total counting; the Java array and the .NET plain enumerable have
  no automatic count. Paging fields are retained but excluded from this common contract.
- Framework-generated 404 bodies differ. Only their HTTP status is asserted.
- Correlation IDs, task IDs, framework headers, JSON property order and local ports are not expected to
  be byte-identical. Relationships and relevant field contracts are asserted without rewriting bodies.

Authorization rejection, authentication schemes, custom validation rules, temporal values beyond UUID
identifiers, null/default semantics, paging/sorting requests, observable transports, database behavior
and cross-store atomicity are outside this slice. The .NET source review was at `03200c1c`
(`v22.14.0-1-g03200c1c`); the package pin, not that local revision, identifies the executed binaries.

## Process and output safety

- Hosts bind loopback using OS-assigned ports. HTTP requests ignore ambient proxies and reject redirects.
- JVM configuration comes from the packaged application properties and an allowlisted environment, not
  `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, or external Spring configuration environment variables.
- A child has a 90-second startup bound and 180-second whole-lifetime bound, including HTTP exchanges.
  Output is drained continuously to a maximum 4 MB log. Each response is limited to 1 MB, with a 20-second
  socket timeout; the whole-child deadline also bounds slowly streamed responses.
- Normal failure, cancellation/SIGTERM and success terminate and join the owned child and its supervisor
  threads. An unresponsive child is killed. SIGKILL of the runner itself cannot guarantee cleanup.
- Evidence must target a nonexistent path in the current task's `.ai-work` keep/output area. Existing or
  symlink destinations are refused. Final publication is atomic without replacement; failed attempts
  retain logs and exchanges in task-owned scratch and never publish a success result.
- Lifecycle may retain protected source/package scratch; do not bypass its cleanup refusals. No automatic
  deletion of existing captures, worktrees, user databases or historical artifacts occurs.

Harness tests run without framework hosts:

```bash
ai-work-lifecycle --repo "$PWD" run-task paired-http-harness -- \
  python3 -B -m unittest discover -s ContractTests/HttpConformance -v
```

They exercise fixture mutation rejection, two-ID query selection, exact no-side-effect snapshots,
loopback/proxy/redirect policy, runtime/readiness errors, timeouts, log bounds, SIGTERM and child cleanup.
They supplement—but never replace—the real three-host gate.
