---
applyTo: "**/*"
---

## The manifest is a transport contract

`ArcArtifactManifest` (in `Source`, `io.cratis.arc.artifacts`) is the language-neutral document that
crosses the boundary from compile time to the Gradle plugin and the TypeScript generator. The code
declares:

```kotlin
@JsonPropertyOrder("formatVersion", "moduleName", "commands", "queries", "types", "interfaces", "enums", "concepts")
public class ArcArtifactManifest ... {
    public companion object {
        /** Current language-neutral manifest contract version. */
        public const val CURRENT_FORMAT_VERSION: Int = <n>
    }
}
```

Read the declared version from `ArcArtifactManifest.CURRENT_FORMAT_VERSION` rather than from this
file; it moves whenever the manifest contract does. Current format 7 adds optional typed command
`eventMetadata`; absence stays absent so event-store fallbacks are not baked into the manifest.
`ArcManifestDiscovery` in `GradlePlugin` enforces
it strictly on read and will fail the build with a `GradleException` when a manifest:

- has no numeric `formatVersion`, or one that is not exactly `CURRENT_FORMAT_VERSION`;
- carries legacy flat fields (`typeName`, `isNullable`, `isEnumerable`, `elementTypeName`,
  `responseTypeName`, `isFromServices`) instead of canonical `shape` / `returnShape` / `source`
  metadata;
- uses an unknown type-shape `kind`, a nullable container entry, a non-`String` or nullable map key,
  a map value leaf outside the safe primitive set, or a map in a context that does not allow one;
- omits the boolean `hasDefault` on a query parameter;
- collides with another manifest on `moduleName`.

Consequences for any change to what the processor writes:

1. Adding, removing, or reshaping a manifest field is a **format change**. Bump
   `CURRENT_FORMAT_VERSION`, update `ArcManifestDiscovery`'s validation, and update the
   `GradlePlugin` tests that assert acceptance and rejection of each format.
2. Never write a field the reader rejects, and never relax the reader to accept output you did not
   intend to produce.
3. The manifest is consumed by released tooling. Treat a version bump as a breaking change and say so
   in the pull request.
