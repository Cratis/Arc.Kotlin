---
applyTo: "**/*"
---

## What the processor generates

Invocation implementations are emitted during processing. `finish()` flushes the final metadata
diagnostics and emits aggregate outputs once, only for a valid, resolved snapshot with a valid
module name and at least one command or query. The output consists of:

- One command handler per command, in `io.cratis.arc.generated.commands`, named
  `<Simple>ArcCommandHandler_<12 hex>` where the suffix is the first six bytes of the SHA-256 of the
  command's fully qualified name.
- One query performer per query, in `io.cratis.arc.generated.queries`, named
  `<method>ArcQueryPerformer_<12 hex>` over the fully qualified query name.
- One internal `io.cratis.arc.generated.<ModuleName>ArcArtifactMetadata` helper, whose factories
  construct fresh command/query descriptors. Invokers keep explicit descriptor types and public
  no-argument constructors; their metadata is stable per instance, not a shared singleton.
- One module class `io.cratis.arc.generated.<ModuleName>ArcArtifactModule` extending
  `io.cratis.arc.artifacts.ArcArtifactModule`, listing handlers, performers, types, enums,
  interfaces, and concepts — plus a
  `META-INF/services/io.cratis.arc.artifacts.ArcArtifactModule` entry so it is discoverable through
  `ServiceLoader`.
- One manifest resource at `META-INF/cratis/arc/<moduleName>.json`.

The helper, module, service entry, and manifest share explicit aggregating dependencies on the
terminal round's files. Replace that file snapshot every round; `Dependencies.ALL_FILES` can retain
invalid first-round source objects in KSP2. Keep per-invoker source associations and do not emit
placeholder files to force stabilization rounds. Provisional diagnostic nodes are likewise replaced
every round and published only through valid terminal callbacks; lifecycle changes require native
KSP error-location and incremental checks, not just embedded compilation tests.

Names are content-addressed and every collection is sorted before rendering (commands by qualified
name, queries by fully qualified name, types/interfaces/enums/concepts by fully qualified name).
Determinism is a contract: `GeneratedArtifactManifestTest` asserts the manifest is deterministic,
complete, and timestamp-free, and the whole TypeScript proxy chain depends on it. Never introduce a
timestamp, a hash of a file path, an iteration order that depends on the file system, or anything
else that can differ between two identical compilations.
