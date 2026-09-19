# Arc for Kotlin and Java

Arc.Kotlin is the JVM implementation of [Arc](https://github.com/Cratis/Arc) — an opinionated CQRS
application framework — for Kotlin and Java applications hosted by Spring Boot. Compile-time
model-bound commands and queries, generated TypeScript clients, servlet hosting, optional
persistence and Chronicle integrations, OpenAPI, and in-process test support: everything discovered
by convention instead of hand-wired. It does not claim complete feature parity with Arc on .NET; the
implemented and intentionally unsupported areas are tracked, row by row, with their evidence, in the
[parity reference](Documentation/reference/parity.md).

[![Maven Central](https://img.shields.io/maven-central/v/io.cratis/arc?label=Maven%20Central&logo=apachemaven&logoColor=white)](https://central.sonatype.com/artifact/io.cratis/arc)
[![Kotlin Build](https://github.com/Cratis/Arc.Kotlin/actions/workflows/build.yml/badge.svg)](https://github.com/Cratis/Arc.Kotlin/actions/workflows/build.yml)
[![Publish](https://github.com/Cratis/Arc.Kotlin/actions/workflows/publish.yml/badge.svg)](https://github.com/Cratis/Arc.Kotlin/actions/workflows/publish.yml)
[![Discord](https://img.shields.io/discord/1182595891576717413?label=Discord&logo=discord&logoColor=white)](https://discord.gg/kt4AMpV8WV)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Arc hosts application behavior, executes command and query pipelines over KSP-generated,
reflection-free handlers, and generates TypeScript clients for them from the same compile-time
metadata. Validation, authorization, identity, tenancy, observable queries, OpenAPI, and
observability come from the same pipeline. Chronicle event sourcing, Spring Data JPA/MongoDB, and
OpenAPI are optional integrations layered on top of a Core that has no dependency on any of them.

Arc.Kotlin is part of one deliberately simple Cratis ecosystem — AI-friendly by design, with free
[AI skills](https://github.com/Cratis/AI) for building with the stack.

## Start here

- [Build your first Arc application in Kotlin](Documentation/get-started/index.md) or
  [in Java](Documentation/get-started/java.md) — a runnable command and query in about five minutes.
- [Browse the guides](Documentation/guides/index.md) — commands, queries, security, Spring Data,
  TypeScript proxies, OpenAPI, observability, Chronicle, and in-process testing.
- [Look things up in the reference](Documentation/reference/index.md) — annotations, configuration,
  the HTTP contract, and shared fluent validation.
- [Read the feature parity matrix](Documentation/reference/parity.md) — the honest, evidence-backed
  status of every area of the framework.
- [Run the samples](#running-the-samples) — four runnable applications, one command each.
- [Understand what Arc.Kotlin owns](#what-arckotlin-owns)
- [See the module and package layout](#workspace-and-published-packages)

## What Arc.Kotlin owns

| Boundary | Arc.Kotlin provides |
| --- | --- |
| Hosting | Spring Boot servlet hosting: HTTP, Server-Sent Events, and optional WebSocket, registered by auto-configuration |
| Commands | Model-bound `@Command` classes with regular, `suspend`, or Java `CompletionStage` handlers, validation, authorization, filters, and generated endpoints |
| Queries | Model-bound `@ReadModel` static/companion queries, paging and sorting, GET and RFC QUERY, observable HTTP snapshots, SSE, and WebSocket |
| Validation | Jakarta Bean Validation, reusable `ConceptValidator`/`ModelValidator` rules, shared fluent validators with generated client rules, and command/query pipelines |
| Identity and tenancy | Pluggable `AuthenticationHandler` chains, identity details, role/policy authorization, and header/query/claim/subdomain/fixed/development tenant resolution |
| Generated contracts | Strict-mode TypeScript command/query/model/enum proxies, validation metadata, identity details, and npm package/type mapping |
| Persistence integration | Spring Data JPA and MongoDB read models, paging, observable snapshots, and optional Chronicle-backed event-sourced behavior |
| Evaluation and tooling | OpenAPI 3.1, Micrometer observability, stable `ARCKSP` compile diagnostics, checked `.api` binary baselines, and in-process command/query/observable scenarios |

Each row is a documented capability area, not a promise of raw-output compatibility with Arc .NET —
see the [parity reference](Documentation/reference/parity.md) for the exact, evidence-backed status
of every specific behavior.

## Arc.Kotlin does not require Chronicle

`io.cratis:arc` has no Chronicle dependency. Commands and queries can use Spring Data JPA, Spring
Data MongoDB, or plain application services without an event log. Choose the persistence and
integrations that fit each application.

The `io.cratis:arc-chronicle-spring-boot-starter` integration is optional and supplies event-sourced
behavior when configured: returned events are staged and committed as part of the command pipeline,
Chronicle read models resolve into command handlers, and reactors can execute commands as side
effects. **`io.cratis:cratis` is the preferred single dependency for an event-sourced application** -
it is a pure aggregator over Arc, its Spring Boot wiring, and this Chronicle integration. See the
[Chronicle integration guide](Documentation/guides/chronicle.md).

[Chronicle](https://github.com/Cratis/Chronicle) is Cratis's storage-agnostic event-sourcing database
and runtime — MIT licensed and free to use. This repository consumes it through
[Chronicle.Kotlin](https://github.com/Cratis/Chronicle.Kotlin), the JVM client and Spring Boot
starter; see the [Chronicle documentation](https://www.cratis.io/chronicle/) for its own scope.

## Relationship to Arc on .NET

Arc.Kotlin is a separate implementation of the same ideas as [Arc](https://github.com/Cratis/Arc),
not a generated or mechanically mirrored port. The two frameworks share vocabulary — commands,
queries, model binding, generated TypeScript proxies — but Arc.Kotlin is Kotlin-first and
Java-first-class, targets Spring Boot exclusively, and makes JVM-native choices where the platforms
differ (coroutines and `CompletionStage` instead of `async`/`await`, KSP compile-time diagnostics
instead of Roslyn analyzers, `Flow`/`Flow.Publisher` instead of `IObservable`/`ISubject`).

Every claim about how closely a specific behavior matches Arc .NET is tracked with its supporting
test, contract test, or sample in the [parity reference](Documentation/reference/parity.md). Treat
any other comparison — in this README, in code comments, or in conversation — as informal unless it
points at that document.

## Start an Arc host

Add the plugin and the Spring Boot starter, then annotate a command:

```kotlin
// build.gradle.kts
plugins {
    id("io.cratis.arc") version "<version>"
    kotlin("plugin.spring") version "2.4.10"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

cratisArc {
    moduleName.set("TaskApplication")
    dependencyVersion.set("<version>")
    endpoints {
        segmentsToSkip.set(2)   // drops the 2 "example.tasks" package segments from the route
    }
}

dependencies {
    implementation("io.cratis:arc-spring-boot-starter:<version>")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
```

```properties
# src/main/resources/application.properties
cratis.arc.endpoints.segments-to-skip-for-route=2
```

```kotlin
// src/main/kotlin/example/tasks/CreateTask.kt
package example.tasks

import io.cratis.arc.artifacts.Command
import io.cratis.arc.authorization.AllowAnonymous

data class TaskCreated(val title: String)

@Command
@AllowAnonymous
data class CreateTask(val title: String) {
    fun handle(): TaskCreated = TaskCreated(title)
}
```

An ordinary `@SpringBootApplication` main is the entire host. KSP generates a reflection-free
handler and the route `POST /api/create-task` at build time; nothing is registered by hand. Continue
with the [Kotlin](Documentation/get-started/index.md) or [Java](Documentation/get-started/java.md)
tutorial for a complete command, query, and read model, and use
[GitHub Issues](https://github.com/Cratis/Arc.Kotlin/issues) when observed behavior does not match
the documentation.

## Workspace and published packages

| Project | Published identity | Responsibility |
| --- | --- | --- |
| `:Source` | `io.cratis:arc` | Command, query, validation, authorization, authentication, identity, tenancy, introspection, result, JSON, and artifact contracts |
| `:CodeGeneration:KSP` | `io.cratis:arc-ksp` | Reflection-free command/query generation, manifests, concept and Jakarta validation metadata, stable `ARCKSP` diagnostics, and a checked ABI baseline |
| `:GradlePlugin` | Gradle plugin `io.cratis.arc` (`io.cratis:arc-gradle-plugin`) | JVM/KSP conventions, one-shot plus observable TypeScript proxy generation, and a checked ABI baseline |
| `:Integrations:SpringBoot` | `io.cratis:arc-spring-boot-starter` | Spring Boot auto-configuration and servlet HTTP, SSE, and optional WebSocket hosting |
| `:Integrations:SpringDataJpa` | `io.cratis:arc-spring-data-jpa` | Spring Data JPA paging, read-model, command transaction, and observable `Flow` adapters |
| `:Integrations:SpringDataMongo` | `io.cratis:arc-spring-data-mongodb` | Spring Data MongoDB paging, read-model, command transaction, and change-stream-backed observable `Flow` adapters |
| `:Integrations:OpenApi` | `io.cratis:arc-openapi-spring-boot-starter` | OpenAPI 3.1 generation and cached document routes |
| `:Integrations:Observability` | `io.cratis:arc-observability-spring-boot-starter` | Micrometer observations and optional OpenTelemetry correlation for Arc execution |
| `:Integrations:Chronicle` | `io.cratis:arc-chronicle-spring-boot-starter` | Optional tenant-aware Chronicle transactions, concurrency, read models, command side effects, and scenario support |
| `:Integrations:Cratis` | `io.cratis:cratis` | The one dependency for an event-sourced Cratis application - a pure aggregator over Arc, its Spring Boot wiring, and the Chronicle integration |
| [`:Testing`](Testing/README.md) | `io.cratis:arc-testing` | Reusable command, query, and observable-query scenarios with Kotlin and Java bridges; Chronicle adds an in-memory scenario extender |
| `:ContractTests` | Unpublished | Kotlin and Java generated-artifact, manifest, validation, and consumer contract fixtures |
| [`:Samples:Kotlin:SpringBoot`](Samples/Kotlin/SpringBoot/README.md) | Unpublished | Runnable standalone Kotlin Spring Boot application |
| [`:Samples:Java:SpringBoot`](Samples/Java/SpringBoot/README.md) | Unpublished | Runnable standalone Java Spring Boot application |
| [`:Samples:Kotlin:ChronicleSpringBoot`](Samples/Kotlin/ChronicleSpringBoot/README.md) | Unpublished | Runnable tenant-aware Kotlin Arc + Chronicle application |
| [`:Samples:Java:ChronicleSpringBoot`](Samples/Java/ChronicleSpringBoot/README.md) | Unpublished | Runnable tenant-aware ordinary-Java Arc + Chronicle application |

Arc targets Spring Boot; `Source` (`io.cratis:arc`) is part of that product, not a separate
host-independent Core product. Integrations depend inward on `Source`; samples consume public
starters, and Chronicle remains optional. The compiled `artifacts`, `metadata`, and `json` packages —
and every local type they transitively reference — must remain Spring-free; `./gradlew
checkSpringBoundary` enforces that boundary on every build. This is a compiler/build-tool boundary,
not a promise of another host: see [Non-Spring hosting](Documentation/reference/parity.md) for that
explicit disposition.

## Build

The build uses the checked-in Gradle 8.14.4 wrapper and requires JDK 17. Make a JDK 17 installation
the active `JAVA_HOME`/`PATH`; no repository-specific absolute JDK path is required.

```shell
java -version
./gradlew build --no-configuration-cache
```

Run the documentation-only gate with:

```shell
./Documentation/verify-markdown.sh
```

Supply a release version with `-Pversion=<version>`; local builds default to `0.0.0-SNAPSHOT`.

## Running the samples

One command starts a backend, regenerates its TypeScript proxies, and opens a React frontend against
them:

```shell
./Samples/run.sh                     # Kotlin, in memory, with the frontend on :5173
./Samples/run.sh --language java     # the same application written in Java
./Samples/run.sh --database mongodb  # store the task board in MongoDB instead
./Samples/run.sh --chronicle         # the Chronicle-backed sample, kernel and all
./Samples/run.sh --no-frontend       # backend only, for curl
```

The showcase mirrors the Arc .NET sample application: a live ticker, a message feed, all four query
shapes from one read model, conditional queries, change streams, observable collections keyed by
`Int` and by `UUID`, a protected read model with one deliberately anonymous query, and cross-cutting
authorization through command and query filters. A toolbar switches the transport between WebSocket
and Server-Sent Events, changes the connection count and transfer mode, and signs a user in and out
— and every page keeps working, which is the point.

Both plain Arc hosts serve identical routes, so the one frontend runs against either unchanged.
`./Samples/run.sh --help` lists every option; [`Samples/README.md`](Samples/README.md) explains what
each page demonstrates. Anything a run starts — a database container, the Chronicle kernel, the
frontend — is stopped again on exit.

## Documentation map

- [Documentation index](Documentation/index.md) — module map, current status, and how to choose a
  path through the rest of the docs.
- [Get started](Documentation/get-started/index.md) — the Kotlin and Java tutorials.
- [Guides](Documentation/guides/index.md) — commands, queries, security, Spring Data, TypeScript
  proxies, OpenAPI, observability, Chronicle, and testing.
- [Reference](Documentation/reference/index.md) — annotations, configuration, the HTTP contract, and
  shared fluent validation.
- [Feature parity](Documentation/reference/parity.md) — the complete, evidence-backed status matrix.

## Contributing

Arc.Kotlin is a framework/library repository, not an event-sourced application: changes to public
APIs, KSP-generated output, the artifact manifest, and generated TypeScript proxies affect every
downstream consumer and carry their own review discipline. Start with [`AGENTS.md`](AGENTS.md) and
the project rules under [`.cratis/ai/rules/project`](.cratis/ai/rules/project), which cover
branching, commit and pull-request conventions, where tests live, the manifest as a transport
contract, and the exact gates a change needs to satisfy before merge.

## Community and repository

| Path | Destination |
| --- | --- |
| Questions and discussion | [Cratis Discord](https://discord.gg/kt4AMpV8WV) |
| Bugs and feature requests | [GitHub Issues](https://github.com/Cratis/Arc.Kotlin/issues) |
| Releases | [GitHub Releases](https://github.com/Cratis/Arc.Kotlin/releases) |
| Documentation | [`Documentation/`](Documentation/index.md) |
| Arc on .NET | [github.com/Cratis/Arc](https://github.com/Cratis/Arc) · [Docs](https://www.cratis.io/arc/) |
| License | [`LICENSE`](LICENSE) |

## The Cratis ecosystem

Arc.Kotlin is part of [Cratis](https://www.cratis.io) — free, MIT-licensed tools for building
event-sourced and CQRS applications.

- **[Chronicle](https://github.com/Cratis/Chronicle)** — event-sourcing database and runtime.
  Orleans-based kernel, pluggable storage (MongoDB default; PostgreSQL, SQL Server, SQLite,
  in-memory), language-agnostic gRPC contracts. [Docs](https://www.cratis.io/chronicle/)
- **Chronicle clients** — first-class [.NET SDK](https://github.com/Cratis/Chronicle), plus
  [TypeScript](https://github.com/Cratis/Chronicle.TypeScript),
  [Kotlin/Java](https://github.com/Cratis/Chronicle.Kotlin), and
  [Elixir](https://github.com/Cratis/Chronicle.Elixir); [Python](https://github.com/Cratis/Chronicle.Python)
  coming soon (pre-alpha). AI agents connect through the
  [Chronicle MCP server](https://github.com/Cratis/Chronicle.Mcp).
- **[Arc](https://github.com/Cratis/Arc)** — opinionated CQRS framework for ASP.NET Core with
  commands, queries, validation, authorization, and TypeScript proxy generation. Works without
  event sourcing. [Docs](https://www.cratis.io/arc/)
- **Arc.Kotlin** (this repository) — the JVM implementation of Arc for Kotlin and Java applications
  hosted by Spring Boot.
- **[Components](https://github.com/Cratis/Components)** — React components aligned with Arc
  patterns. [Docs](https://www.cratis.io/components/)
- **[CLI](https://github.com/Cratis/cli) + Workbench** — inspect and diagnose Chronicle from the
  terminal or the browser. [Docs](https://www.cratis.io/cli/)
- **Model-first layer (experimental)** — Studio, [Screenplay](https://github.com/Cratis/Screenplay),
  [Stage](https://github.com/Cratis/Stage), [Scene](https://github.com/Cratis/Scene),
  [Prologue](https://github.com/Cratis/Prologue)
- **Supporting** — [Fundamentals](https://github.com/Cratis/Fundamentals),
  [Specifications](https://github.com/Cratis/Specifications),
  [Synopsis](https://github.com/Cratis/Synopsis), [Lens](https://github.com/Cratis/Lens),
  [Narrator](https://github.com/Cratis/Narrator), and free
  [AI tooling](https://github.com/Cratis/AI) (preview); Ensemble coming soon (pre-release)
- **[Samples](https://github.com/Cratis/Samples)** — runnable event sourcing and CQRS samples for
  the whole stack

Everything Cratis publishes today is MIT licensed and free to use.

Release notes and announcements: the [Cratis blog](https://blog.cratis.io).
