---
applyTo: "**/*"
---

## What "Arc .NET" is

Arc .NET is the reference implementation at `Cratis/Arc`
(read from a local read-only checkout, commonly the sibling `../Arc`). Its own `README.md` describes it as:

> "Arc is an open-source (MIT) opinionated CQRS application framework for ASP.NET Core with commands,
> queries, validation, authorization, and TypeScript proxy generation. It works without event
> sourcing, with optional Chronicle integration."

Orientation for reading it — all paths relative to that repository's root:

| Concern | Where it lives in Arc .NET |
| --- | --- |
| Host-neutral pipelines | `Source/DotNET/Arc.Core` (commands, queries, validation, authorization, authentication, identity, tenancy, introspection) |
| ASP.NET Core hosting | `Source/DotNET/Arc` |
| TypeScript proxy generation | `Source/DotNET/Tools/ProxyGenerator` — reflection over the built assembly plus Handlebars templates, run as a `dotnet` tool or an `AfterBuild` MSBuild target |
| Compile-time diagnostics | `Source/DotNET/Arc.Core.CodeAnalysis` (Roslyn analyzers, `ARC*` codes) and `Source/DotNET/Arc.Core.Generators` |
| Optional integrations | `Source/DotNET/{Chronicle,MongoDB,EntityFrameworkCore,OpenApi,Swagger}` |
| Shared client runtime | the npm packages `@cratis/arc`, `@cratis/arc.react`, and `@cratis/fundamentals` |

Two facts that repeatedly cause wrong claims: `ConceptAs<T>` is **not** defined in Arc .NET — it comes
from the external `Cratis.Fundamentals` package, whereas Arc.Kotlin defines its own
`io.cratis.arc.concepts.ConceptAs<T>` in `Source`. And Arc .NET contains **no** parity document,
parity test, or parity gate of its own; the entire parity contract lives in this repository. A
design note under `Arc/.ai-work/` is a local work artifact, not a specification — never cite it as
authority.

**That repository is read-only from here.** Never edit it, never copy its source, documentation, or
`.ai/` content into this repository, and never assume a behavior exists here because it exists there.
