# Arc .NET proxy capture harness

This harness builds only the repository-authored `Fixture.cs`, using published NuGet packages.
It never reads, builds, installs from, or copies from a sibling Arc checkout. It does not update
`../captured` or `../dotnet`. Ordinary JVM tests check the reviewed seven-file baseline offline; an
actual .NET capture remains an explicit operation.

## Run

Requires Python 3.9+, the exact SDK/runtime below, network access to nuget.org, and the local
`ai-work-lifecycle` CLI. From the physical repository root (no symlink components):

```bash
ai-work-lifecycle --repo "$PWD" run-task capture-example -- sh -c \
  'GradlePlugin/src/test/resources/differential/capture/capture.sh --output "$AI_WORK_KEEP/capture"'
```

Use a unique task name each time. `--output` is mandatory and must name a **nonexistent directory**
under this repository's `.ai-work/`, with an existing parent. Existing captures, empty directories,
manual directories, files, symlinks (including ancestor/dangling links), and paths outside `.ai-work/`
are refused. There is no overwrite or cleanup switch. Historical snapshots cannot be destinations.

Work goes into a newly allocated directory inside lifecycle's `AI_WORK_OUTPUT`; no system `mktemp`
or recursive deletion is used. Only after generation, normalization, and verification succeed is the
complete staged tree atomically published with no replacement, even if another process creates an
empty destination between validation and publication. This needs macOS `renamex_np` or Linux
`renameat2`, with staging and destination on the same filesystem. Other hosts fail closed. This is
not a security boundary against a hostile same-user process swapping ancestor directories.

The harness leaves all scratch cleanup to lifecycle. Its conservative cleanup may retain copied C#
sources, extracted packages, or NuGet scratch files for review; read its cleanup result. Failed runs
leave recovery evidence and no published capture. Do not infer that retained output is disposable
merely because a command exited.

## Pins and isolation

| Input | Pin |
| --- | --- |
| .NET SDK | `global.json`: 10.0.400, roll-forward disabled, no prereleases |
| Runtime / reflection reference sets | Microsoft.NETCore.App and Microsoft.AspNetCore.App 10.0.11 |
| `Cratis.Arc` | exact `[22.14.0]` PackageReference |
| `Cratis.Arc.ProxyGenerator` | `tool.lock.json`: 22.14.0, archive URL and SHA-256 |
| Fixture dependency graph | `packages.lock.json`, restored with `--locked-mode` |
| Signed dependency archive bytes | `packages.sha256.json`, 100 package SHA-256 checksums |
| NuGet sources | `NuGet.Config`: nuget.org only, cleared fallback folders/source mapping |

The SDK is selected from a copied `global.json` in the private project directory and checked.
Both runtime versions must be installed. The tool runs with `dotnet exec --fx-version 10.0.11
--roll-forward Disable`. Its logged MetadataLoadContext reference directories must also identify
those exact framework versions; missing, duplicate, or different resolution fails capture. Merely
having a version installed is not treated as proof that reflection used it.

The generator's published tool archive bundles its dependency closure. Its bytes are verified before
extracting only `tools/net10.0/any/`; no global/local dotnet tool installation or tool-manifest search
occurs. Fixture restore uses only the explicit NuGet config and the standard shared package cache,
then checks the package-only assets graph, archive SHA-256, and extracted package payload bytes before
publishing. NuGet's lockfile `contentHash` is a logical package hash, **not necessarily the hash of the
signed archive**; these are deliberately separate checks.

The subprocess environment is allowlisted, ancestor Directory.Build/Directory.Packages imports are
disabled, and publish uses `--no-restore`. No source/project reference to Arc is involved. NuGet audit
is disabled for this capture-only project to avoid an unrelated changing advisory feed; this is not
a security audit. Shared cache corruption fails rather than being silently repaired.

Pin updates are deliberate reviews: resolve the changed project in a new lifecycle workspace with
locked mode disabled, review the entire generated dependency lock, record SHA-256 for every resolved
signed archive, and replace the reviewed lock files here. Normal capture never regenerates locks or
falls forward to another SDK, runtime, tool, or package version. Archive checksums are integrity pins,
not signatures or a claim of independently authenticated publisher identity.

## Output and exact normalization

A capture contains:

- `raw/`: untouched generator bytes, including timestamps and original body checksums.
- `normalized/`: the expected-side candidate; only the first header line's `Time:` field is removed.
- `inputs/`: the exact fixture, project, scripts, SDK/NuGet configuration, and all locks used.
- `capture-manifest.json`: format 2, tool/archive/SDK/runtime/platform provenance, input hashes,
  raw and normalized inventories, and uppercase SHA-256 of every other output file.

Every non-index `.ts` file must have exactly one first-line header with a source identity, a valid UTC
seven-fractional-digit timestamp, and an uppercase 64-hex body SHA-256. The body checksum is verified
**before** normalization. Header syntax drift, duplicates, wrong checksums, BOM/CRLF headers, and
unexpected outputs fail closed. Headerless `index.ts` files accept only the generator's exact export
line grammar. Bodies, whitespace, casing, paths, imports, and checksums are not rewritten. Nothing is
applied to JVM output.

Two equivalent runs should have identical `normalized/` bytes and matching input/tool/runtime pins.
Their raw timestamps, raw hashes, and therefore full manifests will differ. Do not claim complete
capture trees or raw generator bytes are deterministic.

Verify a retained capture without building or downloading anything:

```bash
GradlePlugin/src/test/resources/differential/capture/capture.sh --verify /absolute/.ai-work/path/capture
```

Verification checks the exact inventory, hashes, and rederives every normalized file from its raw
counterpart. This detects accidental corruption; a manifest is not an attestation and can be replaced
along with its files. Consumers should additionally compare `inputHashes` against reviewed harness
inputs and enforce their expected tool/runtime/package pins. The JVM differential should consume
only `normalized/` after provenance validation, keeping any semantic expected-side preparation
separate and never normalizing actual JVM output. Integration and snapshot promotion are separate
reviewed changes; this harness makes no claim of raw .NET/JVM output equivalence.

## Offline baseline consistency gate

`./gradlew :GradlePlugin:verifyCapturedProxyBaseline` runs as a dependency of `:GradlePlugin:test`.
It first exercises the verifier's mutation tests, then checks `baseline-receipt.json` against:

- The bytes of every current generation input in `capture.INPUTS`, including the fixture, project,
  capture scripts, SDK/NuGet configuration, and dependency/tool locks.
- The configured SDK, runtime/reflector, package, generator argument, and tool-archive pins.
- The exact seven paths and bytes in `../captured`, plus that snapshot's existing format-1 metadata.

No dotnet command, network request, package-cache access, local `.ai-work` capture, or output rewrite
is part of normal checking. Python 3 is required, as it already is for the test-declaration checker.
Unit-test scratch is isolated in temporary directories under `build/capture-baseline-tests`; local
managed checks may set `-Parc.captureBaseline.testWork=<lifecycle-output-directory>`. The verifier
itself only reads files. It does not adopt the older nineteen-file `../dotnet` fixture, whose original
capture provenance remains different and incomplete.

A receipt is a **reviewed consistency fixture, not an attestation**. Its capture-manifest digest names
retained evidence but cannot re-prove tool execution without that full capture. Replacing the receipt
and its inputs together can forge the claim; code review is still required. Ordinary checking prevents
accidental/stale drift, not malicious coordinated edits to the checker, fixture and receipt. Raw bytes
are verified only when producing a candidate from a full capture.

When generation inputs change, make a new explicit capture with the pinned tool, then prepare a
candidate receipt without overwriting either reviewed baseline:

```bash
python3 -B verify_baseline.py --candidate /absolute/.ai-work/path/new-capture \
  > /absolute/.ai-work/path/candidate-receipt.json
```

Candidate generation verifies the complete format-2 raw/normalized inventory and timestamp removal,
requires input hashes to match today's source and all metadata pins, then writes only JSON to stdout.
Review the candidate and normalized proxy diff before updating `baseline-receipt.json` and, if needed,
the seven-file fixture in one change. Never recompute the receipt from a snapshot alone or update input
hashes just to clear the gate. The receipt/checker/tests themselves are not generator inputs, avoiding
a self-referential hash while keeping every source actually used to produce proxies bound.

## Harness tests

```bash
ai-work-lifecycle --repo "$PWD" run-task capture-tests -- \
  python3 -B -m unittest discover -s GradlePlugin/src/test/resources/differential/capture -v
```

Tests cover checksum corruption (including extracted cache bytes), exact normalization and index
syntax, malformed headers, unsafe destinations, runtime resolution, tool archive traversal,
subprocess/IO failures, publication races, and rehashed normalized-file tampering. They use only
Python's standard library and lifecycle-owned temporary directories; no .NET installation is needed
for these unit tests. The additional `test_baseline.py` cases run automatically before
`:GradlePlugin:test`; the standalone capture-harness tests still run through the explicit command above.
