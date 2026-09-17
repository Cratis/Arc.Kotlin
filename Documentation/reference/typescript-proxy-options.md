---
title: TypeScript proxy options compatibility matrix
description: Classification of every proxy generator option and model-shape boundary against Arc .NET, with pinned reference versions, enforcing guards, and a precise slice definition for anything implementable but not yet present.
---

## Pinned reference versions

| Side | Component | Pinned version | Source |
| --- | --- | --- | --- |
| JVM | `@cratis/arc` and `@cratis/arc.react` | 22.10.4 | `ContractTests/TypeScript/package.json` |
| JVM | `@cratis/fundamentals` | 7.18.4 | `ContractTests/TypeScript/package.json` |
| .NET | `Cratis.Arc.ProxyGenerator` | 22.14.0 | `parity.md` capture harness; `GradlePlugin/src/test/resources/differential/capture/capture.sh` |
| .NET | .NET SDK | 10.0.400 | Same capture harness |
| .NET reference path | `Source/DotNET/Tools/ProxyGenerator/` | `Cratis/Arc` read-only local checkout | `Program.cs`, `TypeExtensions.cs`, `Generator.cs` read directly |

Every row below was verified against code read at these versions. The .NET `Program.cs` usage string is quoted in the option table; `TypeExtensions.IsExcluded`, `SetNamespaceRoots`, and `ResolveTargetPath` were read from `TypeExtensions.cs`; and the JVM guards were read from `TypeScriptProxyGenerator.kt` in this repository. No source was assumed to match without reading it.

## Status key

| Status | Meaning |
| --- | --- |
| Supported today | Present and working in the current JVM generator. |
| JVM-specific | Supported with a design that differs intentionally from .NET. |
| Deliberately unsupported | No equivalent; reason and the enforcing guard are named. Do not remove these guards. |
| Implementable with named slice | Not present; a named slice below defines exactly what to build, what would prove it, and what it must not break. |

## `segmentsToSkip`: file layout versus route

Arc .NET exposes one positional `segments-to-skip` argument that drops the same number of leading
namespace segments from **both** the generated file path and the computed route. The JVM splits this
into two independent settings with separate CLI options:

| Setting | CLI option | Controls | Forwarded to |
| --- | --- | --- | --- |
| `endpoints.segmentsToSkip` | `--route-segments-to-skip` | Leading package segments dropped from **routes** | `ApiEndpointOptions.segmentsToSkipForRoute` → `EndpointRouteHelper` |
| `proxies.segmentsToSkip` | `--proxy-segments-to-skip` | Leading package segments dropped from generated **file paths** | `ProxyGenerationOptions.segmentsToSkip` → `TypeScriptProxyGenerator.outputDirectory()` |

Setting one and expecting the other to follow will not work: they are independent. Both default to `0`.
The contract tests fix both at `5` via the `proxyArguments()` block in `GradlePlugin/build.gradle.kts`.
The `--namespace-root` slice described later provides a named-root alternative to `segmentsToSkip`
for file layout.

A project that wants routes and file paths to diverge — for example, a route at `/api/features/orders`
but a proxy at `orders/Orders.ts` — uses a different value for each setting. This split has no .NET
equivalent.

```kotlin
cratisArc {
    endpoints {
        segmentsToSkip = 3   // drops 3 leading segments from generated ROUTES
    }
    proxies {
        segmentsToSkip = 3   // drops 3 leading segments from generated FILE PATHS
    }
}
```

## Option compatibility matrix

The Arc .NET `Program.cs` usage line reads verbatim:

```shell
Cratis.ProxyGenerator <assembly> <output-path> [segments-to-skip] [--library-mode]
  [--skip-output-deletion] [--skip-command-name-in-route] [--skip-query-name-in-route]
  [--api-prefix=<prefix>] [--skip-index-generation] [--use-source-file-as-output-file]
  [--emit-interfaces] [--assembly-to-package=<Assembly>=<Package>]...
  [--exclude-type=<FullyQualifiedTypeName>]... [--exclude-namespace=<Pattern>]...
  [--namespace-root=<Namespace>=<Folder>]... [--type-to-ts=<FullyQualifiedTypeName>=<TsType>[=<Package>]]...
```

| .NET option | JVM option / DSL | Status | Notes |
| --- | --- | --- | --- |
| `segments-to-skip` (positional) | `endpoints.segmentsToSkip` + `proxies.segmentsToSkip` | JVM-specific | .NET applies one value to both route and file path. The JVM requires two separate values. See the previous section. |
| `--api-prefix=<prefix>` | `endpoints.routePrefix` / `--route-prefix` | Supported today | Both default to `api`. |
| `--skip-command-name-in-route` | `endpoints.includeCommandNames = false` | Supported today | Polarity is inverted; semantics are identical. |
| `--skip-query-name-in-route` | `endpoints.includeQueryNames = false` | Supported today | Polarity is inverted; semantics are identical. |
| `--skip-output-deletion` | `proxies.removeStaleGeneratedFiles = false` | Supported today | Polarity is inverted; semantics are identical. |
| `--type-to-ts=<Type>=<TsType>[=<Package>]` | `proxies.mapType(...)` / `--type-to-typescript` | Supported today | Identical semantics: consulted ahead of the built-in type map; bounded three-part `=` split; warns and skips unusable entries. `ProxyTypeMappings.parseTypeMappings()` mirrors the .NET bounded-split comment exactly. |
| `--assembly-to-package=<Assembly>=<Package>` | `proxies.mapPackage(jvmPackage, npmPackage)` / `--package-to-npm` | JVM-specific | .NET keys on the assembly name (e.g., `MyLib`). The JVM has no assembly concept: it keys on a fully qualified JVM package prefix (e.g., `com.example.shared`), and the longest matching prefix wins. `parity.md` records this divergence in the "External TypeScript package mappings and type overrides" row. |
| `--exclude-type=<FullyQualifiedTypeName>` | None | Deliberately unsupported | The JVM generates only types that appear in the KSP-produced manifest. A manifest type referenced from a command, query, or model cannot be excluded without also providing a TypeScript replacement: `resolveType()` in `TypeScriptProxyGenerator.kt` throws `GradleException("Unsupported Arc proxy type '...' in '...'.")` for any unresolvable reference. Use `mapType()` to redirect a type to an external npm import instead. |
| `--exclude-namespace=<Pattern>` | None | Deliberately unsupported | Same reasoning as `--exclude-type`. Use `mapPackage()` to redirect an entire JVM package to an external npm package. The same `resolveType()` guard applies for unresolvable types. |
| `--namespace-root=<Namespace>=<Folder>` | None | Implementable with named slice **proxy-namespace-roots** | See the named-slice section below. |
| `--skip-index-generation` | None | Deliberately unsupported | `TypeScriptProxyGenerator.updateIndexFiles()` is called unconditionally from `generate()`. Barrel index files are part of the consumer-facing import contract; suppressing them breaks directory-level imports. There is no flag to skip this behavior and no plan to add one. |
| `--library-mode` | None | Deliberately unsupported | Arc .NET emits proxies for every public type in the loaded assembly under `--library-mode`. The JVM model is annotation-driven: only types reachable from `@Command`, `@ReadModel`, and `@ExportedType`-annotated sources enter the KSP manifest. Bulk emission without annotation is outside the JVM model. |
| `--emit-interfaces` | None | Deliberately unsupported | The JVM emits model classes with `@field` decorators from `@cratis/fundamentals` 7.18.4 because the client runtime uses them for JSON hydration. Arc .NET's `--emit-interfaces` strips `@field` and the runtime dependency for packages that never deserialize. The JVM does not expose this mode because it would break the client hydration contract. |
| `--use-source-file-as-output-file` | None | Deliberately unsupported | The JVM emits one `.ts` file per type. Grouping by source file is a C# idiom (a single `.cs` file may define multiple types); the JVM has no analogous concept and no grouping mechanism. |

## Model-shape compatibility matrix

| Shape | Status | Current JVM contract and enforcing guard |
| --- | --- | --- |
| Class-hierarchy base type (`extends`) | Supported today | `TypeDescriptor.baseTypeName` carries the qualified base class name. `renderType()` emits `export class Foo extends Base { … }`. `resolveBaseType()` requires the resolved base to be an `ArtifactKind.TYPE`; if not, it throws `GradleException("Arc base type '...' must be a generated model type.")`. |
| External (mapped) type as base class | Deliberately unsupported | `resolveBaseType()` in `TypeScriptProxyGenerator.kt` throws for any resolved base that is not a generated `ArtifactKind.TYPE`. Emitting `extends ExternalType` would require `@field` constructor metadata the generator cannot produce for types it does not own. Do not remove this guard. |
| Polymorphic derivative hydration (manifest-registered types) | Supported today | `@DerivedType` writes a discriminator; KSP records the derivative mapping in the manifest; `renderType()` emits `@field(Constructor, isEnumerable, [Derivatives…])`; `DerivedTypeRegistry` handles runtime hydration. See `parity.md` "Polymorphic derived types" row for the JVM-specific `DerivedTypeRegistrar` requirement. |
| External (mapped) type as derivative | Deliberately unsupported | `resolveDerivatives()` in `TypeScriptProxyGenerator.kt` throws `GradleException("Arc derivative '...' must be a generated concrete model type.")` for any type that is not an `ArtifactKind.TYPE`. Do not remove this guard. |
| Map with nonnullable String keys and safe primitive scalar values | Supported today | `resolveMapShape()` accepts `MAP_STRING_TYPE_NAMES` (`kotlin.String`, `java.lang.String`, `String`) keys and `MAP_SAFE_PRIMITIVE_TYPE_NAMES` value leaves (boolean, byte, char, int, short, String and their JVM/Kotlin variants). A runtime prototype-pollution guard is injected as `sanitizeArcStringMap()`. |
| Map with non-String keys | Deliberately unsupported | `resolveMapShape()` throws `GradleException("Map property '...' entry path '...key' must use nonnullable String keys.")` when `keyCodec != MapKeyCodec.STRING` or the key type is outside `MAP_STRING_TYPE_NAMES`. Arc .NET emits `ValueMap<K,V>` from `@cratis/fundamentals` for non-string-key maps; the pinned `@cratis/fundamentals` 7.18.4 client exports `ValueMap`, but the JVM manifest carries no non-string key codec so the generator cannot produce correct `ValueMap` output. Do not remove this guard. |
| Map with typed model or interface values | Deliberately unsupported | `resolveMapShape()` throws `GradleException("Map property '...' entry path '...' has unsupported value leaf '...'.")` for value types outside `MAP_SAFE_PRIMITIVE_TYPE_NAMES`. Typed model values would require nested `@field`/hydration metadata on the map value path; the current map shape does not emit these. |
| Map with nullable entry paths | Deliberately unsupported | `resolveMapShape()` throws `GradleException("Map property '...' entry path '...' cannot be nullable.")` for any nullable path beyond the map root. |
| Sequence of sequences (nested arrays as map value) | Supported today | `TypeShapeKind.SEQUENCE` nested inside another `SEQUENCE` in `resolveMapShape()` is handled: the element shape is resolved recursively and the TypeScript type becomes `element[]`. |
| Map in a shared-validator context | Deliberately unsupported | `appendSharedEdge()` throws `GradleException("[ARCVALIDATION_GRAPH] Shared model maps are unsupported.")`. Client-side shared validation does not traverse map entries. |
| Interface declared properties | Supported today | `InterfaceDescriptor.properties` generates a TypeScript `export interface` block via `renderInterface()`. Properties are emitted in declaration order with optional markers. |
| Interface inheritance (`extends` between interfaces) | Deliberately unsupported | `InterfaceDescriptor` carries no base-interface field. Manifest format 8 records no interface inheritance chain. Emitted TypeScript interfaces have no `extends` clause. Sort helpers for interface return types include only the interface's own declared properties; `returnedSortProperties()` contains the comment `// Interface inheritance is not represented by the manifest; do not invent missing members.` A format bump and renderer change would both be required before this could be supported. |
| Result-field sort helpers (class return type, including class-base chain) | Supported today | `returnedSortProperties()` walks the `TypeDescriptor.baseTypeName` chain of the return type, collecting all declared properties at every level into a sorted set. `${ClassName}SortBy` and `${ClassName}SortByWithoutQuery` helpers are generated for every named field. Cyclic inheritance is detected and throws `GradleException`. |
| Result-field sort helpers (interface return type) | Supported today, with boundary | When the return type resolves to an `InterfaceDescriptor`, `returnedSortProperties()` reads only its declared properties. It does not walk interface inheritance because the manifest records no inheritance chain. Sort keys reflect only the declared members of that specific interface. |
| Result-field sort helpers (external or dependency-only return type) | Deliberately unsupported | When the return type resolves to an external (mapped) type, `returnedSortProperties()` finds no entry in `artifacts.types` or `artifacts.interfaces` and falls through without producing sort helpers. No exception is thrown; the `supportsSorting` capability flag remains authoritative, but the generated helper classes are empty. |
| Types from dependency libraries built with Arc KSP | Supported today | `ArcManifestDiscovery.discover()` scans `META-INF/cratis/arc/*.json` from every directory and jar on the configured manifest classpath, including dependency jars. Types from libraries that ran Arc KSP and produced a manifest are fully discoverable. |
| Types from dependency libraries not built with Arc KSP | Deliberately unsupported | Libraries that were not built with Arc KSP produce no `META-INF/cratis/arc/*.json` manifest entry. `ArcManifestDiscovery` cannot discover their types and the generator cannot produce proxies for them. Use `mapPackage()` or `mapType()` to import such types from a published npm package. The `arc.responseHandlerMetadata` dependency handler index handles response-handler classification from binaries separately; it is not a model-type manifest. |

## Named slice: proxy-namespace-roots

Arc .NET's `--namespace-root=<Namespace>=<Folder>` (implemented in `TypeExtensions.ResolveTargetPath()`)
strips a namespace prefix from the output path and places the remainder under a named folder. When
multiple roots are configured the longest matching prefix wins. This is a file-layout control only
and does not affect routes.

**What the JVM slice would do.**

1. Add `namespaceRoots: List<Pair<String, String>>` to `ProxyGenerationOptions` and a corresponding
   `proxies.mapNamespaceRoot(jvmPackage: String, outputFolder: String)` method on `ArcProxyOptions`.
2. Update `outputDirectory(location: List<String>)` in `TypeScriptProxyGenerator.kt` to: join the
   `location` list back into a dot-separated package string, attempt to match the longest configured
   root whose value is a strict package-boundary prefix of that string, build the output path from
   `outputFolder + remainder`, and fall back to the existing `segmentsToSkip` drop when no root
   matches.
3. Add `--namespace-root` as a repeatable `<jvmPackage=outputFolder>` option to `GenerateArcProxiesCli.kt`.
4. Apply the existing `validateTarget()` safe-path and root-escape guards unchanged to every segment
   produced by the new logic; the path-escape check (`!destination.startsWith(root)`) is unconditional.

**What would prove it.**

- At least two generator unit tests: one asserting that a type whose package matches a root lands
  under the mapped folder; one asserting that a type outside every root still uses `segmentsToSkip`.
- A test asserting that a root whose folder component would produce an unsafe segment is rejected
  before output writes.
- `GradlePlugin:verifyContractTestProxyDeterminism` stays green (root matching must produce a
  consistent sort order across consecutive runs).
- No dangling imports, path escapes, or `index.ts` collisions in the output tree.

**What it must not break.**

- The `segmentsToSkip` fallback for types outside every configured root.
- The `validateTarget()` path-safety and escape guards.
- The `sanitizeArcStringMap` guard and all existing map-property protections.
- The proxy differential gate (`:GradlePlugin:test`) and strict TypeScript compile gate
  (`:ContractTests:typeScriptBuild`).

## Relationship to parity.md

The "External TypeScript package mappings and type overrides" row in `parity.md` already records:
"The JVM keys package mappings on a JVM package prefix because it has no assembly identity to key
on; .NET's `--exclude-type`, `--exclude-namespace`, and `--namespace-root` options have no
equivalent and none is claimed." This matrix extends that record with per-option rationale, named
enforcing guards, and the `proxy-namespace-roots` slice definition.

No parity status changes follow from this document alone. A status upgrade requires a test, contract
test, or runnable sample that proves the behavior, named in the same change. Do not interpret this
matrix as a claim that all TypeScript mappings now match Arc .NET.
