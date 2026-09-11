---
applyTo: "**/*"
---

## What the processor reads

`process(resolver)` replaces its metadata graph and provisional diagnostics each round:

1. Validate configuration and inspect command-like types with the existing diagnostics.
2. Accumulate stable command, read-model, derivative, and source-visible response-handler names;
   resolve them through the current resolver rather than retaining earlier-round semantic symbols.
3. Emit each valid invocation implementation once, while rebuilding response classification and the
   reachable metadata graph against the current discoveries. Handled-only response graphs are not
   retained unless another retained root reaches them.
4. Keep genuine unresolved symbols deferred, including explicit handler-annotation deferrals; do not
   discard a reported deferral merely because a second declaration-level `validate()` succeeds.

Command properties, type/interface properties, query descriptors, factories, and the manifest must
use the same reconstructed metadata. Concepts are a distinct successful collection category and do
not require an ordinary `TypeModel`. Do not repair only one cached list or the manifest.

Verified annotation fully-qualified names the processor reacts to:

| Annotation | Purpose in generation |
| --- | --- |
| `io.cratis.arc.artifacts.Command` | Marks a command; drives handler generation |
| `io.cratis.arc.artifacts.CommandKey` | Marks the command key property |
| `io.cratis.arc.artifacts.CommandEventSourceType` | Static default event-source type for returned events |
| `io.cratis.arc.artifacts.CommandEventStreamType` | Static default event-stream type for returned events |
| `io.cratis.arc.artifacts.CommandEventStreamId` | Static default event-stream ID; conflicts with `CommandEventStreamIdProvider` |
| `io.cratis.arc.artifacts.CommandEventSubject` | Static default event subject; conflicts with `CommandEventSubjectProvider` |
| `io.cratis.arc.artifacts.ReadModel` | Marks a read model; drives query performer generation |
| `io.cratis.arc.artifacts.FromServices` | Marks a handler or query parameter as service-resolved |
| `io.cratis.arc.artifacts.TreatWarningsAsErrors` | Escalates validation severity metadata |
| `io.cratis.arc.authorization.Authorize` | Authorization policy metadata |
| `io.cratis.arc.authorization.Roles` / `RolesContainer` | Role metadata (repeatable) |
| `io.cratis.arc.authorization.AllowAnonymous` | Anonymous access metadata |
| `io.cratis.arc.queries.Path` | Explicit query route; must be unique |
| `io.cratis.arc.queries.QueryHttpMethod` | GET versus RFC QUERY preference |
| `io.cratis.arc.queries.QueryTransport` | Request-response versus observable transport |
| `io.cratis.arc.commands.HandlesCommandResponseValues` | Declarative response-value handler |

Jakarta validation constraints on command properties and query parameters are read separately by
`ValidationMetadataExtractor` and projected into `ValidationRuleDescriptor` metadata.
