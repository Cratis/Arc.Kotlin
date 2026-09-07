# Glossary

This file fixes the vocabulary of Arc.Kotlin and the surrounding Cratis ecosystem so an agent never
has to guess what a word means here. Every entry is grounded in a real type, annotation, route, or
document in this repository, with the path given. Where a Cratis word means something different in
Arc than in ordinary usage — *artifact*, *concept*, *proxy*, *reactor*, *identity provider* — the
Arc meaning is the one that applies. If a term is missing, read the cited neighbors before inventing
one. Paths are relative to the repository root; `Source/…` abbreviates
`Source/src/main/kotlin/io/cratis/arc/…`.

## Products and repositories

| Term | Meaning here | Grounded in |
| --- | --- | --- |
| **Arc** | Cratis' opinionated CQRS application framework: commands, queries, validation, authorization, identity, tenancy, and generated TypeScript clients. Works without event sourcing. | `README.md` |
| **Arc.Kotlin** | This repository — the JVM implementation of Arc for Kotlin and Java on Spring Boot. | `README.md`, `AGENTS.md` |
| **Arc .NET** | The reference implementation at `Cratis/Arc`. Read-only from here; parity with it is a claim requiring evidence. | `Documentation/reference/parity.md`, [arc-parity.md](./arc-parity.md) |
| **Chronicle** | Cratis' event sourcing product. Arc does not require it; `Integrations/Chronicle` is the optional bridge, and `Source` has no Chronicle dependency. | `Documentation/guides/chronicle.md`, `AGENTS.md` |
| **Chronicle.Kotlin** | The released Chronicle JVM client that the Arc Chronicle starter depends on (`io.cratis:chronicle-spring-boot-starter`), supplying `IEventStore` and `ChronicleOptions`. | `Integrations/Chronicle/build.gradle.kts` |
| **Fundamentals** | The shared client package `@cratis/fundamentals`, source of `Guid`, `DateOnly`, and `TimeOnly` in generated TypeScript. | `Documentation/guides/typescript-proxies.md` |
| **Starter** | A published Spring Boot integration artifact, e.g. `io.cratis:arc-spring-boot-starter`. | `Documentation/reference/configuration.md` |

## Artifacts and code generation

| Term | Meaning here | Grounded in |
| --- | --- | --- |
| **Artifact** | A command or a query that Arc recognizes and generates code for. Not a build output. | `Source/artifacts/`, `Documentation/reference/annotations.md` |
| **Artifact module** | `ArcArtifactModule` — the build-time generated collection of a compilation module's handlers, performers, and descriptors, discovered through `ServiceLoader` and Spring beans. | `Source/artifacts/ArcArtifactModule.kt`, `Integrations/SpringBoot/…/ArcArtifactModules.kt` |
| **Manifest** | `ArcArtifactManifest` — the language-neutral JSON description of one module's artifacts, written to `META-INF/cratis/arc/<module>.json` and read by the proxy generator. Current `CURRENT_FORMAT_VERSION` is `5`. | `Source/artifacts/ArcArtifactManifest.kt` |
| **Module name** | The stable KSP artifact module name (`arc.moduleName` KSP argument, `cratisArc.moduleName` in Gradle) that names the manifest file. | `Documentation/reference/configuration.md` |
| **Descriptor** | An immutable metadata record in `io.cratis.arc.metadata` (`CommandDescriptor`, `QueryDescriptor`, `ParameterDescriptor`, `TypeShapeDescriptor`, …) shared by runtime, manifest, proxies, and OpenAPI. | `Source/metadata/` |
| **Model-bound** | Arc's discovery style: behavior lives on the model itself (a `@Command` class with `handle`, a `@ReadModel` with static query methods) instead of in a controller. | `Documentation/guides/commands.md` |
| **KSP** | Kotlin Symbol Processing — the compile-time processor in `CodeGeneration/KSP` that generates reflection-free handlers, performers, and manifests. | `CodeGeneration/KSP/`, `.ai/rules/ksp.md` |
| **`ARCKSP` diagnostic** | A stable compile-time error or warning code emitted by Arc's KSP processor, e.g. `ARCKSP0109` (ambiguous command response values). | `CodeGeneration/KSP/DIAGNOSTICS.md` |
| **Proxy** | Generated TypeScript client code (commands, queries, observable queries, models, interfaces, enums) produced from manifests by the `io.cratis.arc` Gradle plugin's `generateArcProxies`. | `Documentation/guides/typescript-proxies.md` |
| **`.api` baseline** | A checked-in binary-compatibility snapshot of a published module's public API, verified by `apiCheck` and updated deliberately with `apiDump`. | `Source/api/Source.api`, `Documentation/reference/configuration.md` |

## Commands

| Term | Meaning here | Grounded in |
| --- | --- | --- |
| **Command** | A class or Java record annotated `@Command` with one public instance `handle` method. Executed over HTTP `POST`. | `Source/artifacts/Command.kt`, `Documentation/guides/commands.md` |
| **Command handler** | `CommandHandler` — the build-time generated, reflection-free invoker for one command type. Not a class you write. | `Source/commands/CommandHandler.kt` |
| **Command pipeline** | `CommandPipeline` (default `DefaultCommandPipeline`) — the host-agnostic execution path: authorization filter, filters, validation, `provide`, `handle`, response-value handling, execution scopes. `validate` runs it without the handler. | `Source/commands/CommandPipeline.kt`, `Source/commands/DefaultCommandPipeline.kt` |
| **Command filter** | `CommandFilter` — a pre-handler stage returning a `CommandResult` fragment; `AuthorizationCommandFilter` marks the ones that run first. | `Source/commands/CommandFilter.kt` |
| **Command execution scope** | `CommandExecutionScope` — brackets an execution with a host lifetime concern such as a transaction; `begin` before filters, `complete` in reverse order. | `Source/commands/CommandExecutionScope.kt` |
| **Command key** | The value identifying a command instance, taken from a `@CommandKey` member or a `CommandKeyProvider`. Required for plain Chronicle event responses. | `Source/artifacts/CommandKey.kt`, `Source/commands/CommandKeyProvider.kt` |
| **`provide`** | The optional public instance method that fetches or computes values for `handle`, after authorization and validation. Its results are matched to `handle` parameters in declaration order. | `Documentation/guides/commands.md` |
| **Provided values** | `CommandProvidedValues` — an ordered aggregate returned by `provide`; `commandProvidedValuesOf(…)` in Kotlin, `CommandProvidedValues.of(…)` from Java. | `Source/commands/CommandProvidedValues.kt` |
| **Response values** | `CommandResponseValues` and typed `Pair`/`Triple`/`ArcOneOf` aggregates returned by `handle`. Recursively flattened in declaration order. | `Source/commands/CommandResponseValues.kt`, `Source/commands/ArcOneOf.kt` |
| **Client leaf** | The single flattened response value sent to the client. Server-consumed leaves are classified by `@HandlesCommandResponseValues`; two possible client leaves fail with `ARCKSP0109`. | `Source/commands/HandlesCommandResponseValues.kt`, `Documentation/guides/commands.md` |
| **Command result** | `CommandResult<T>` — the JSON envelope every command returns: `correlationId`, `isAuthorized`, `validationResults`, `exceptionMessages`, `isSuccess`, optional `response`. | `Source/results/CommandResult.kt`, `Documentation/reference/http-contract.md` |
| **Service resolver** | `ServiceResolver` — the DI-neutral lookup used by generated handlers; `SpringServiceResolver` implements it over the application context. | `Source/commands/ServiceResolver.kt` |

## Queries and read models

| Term | Meaning here | Grounded in |
| --- | --- | --- |
| **Read model** | A type annotated `@ReadModel` whose static Java or `@JvmStatic` companion methods are its queries. It is the query's result shape, not a persistence layer. | `Source/artifacts/ReadModel.kt`, `Documentation/guides/queries.md` |
| **Query** | A query method on a read model, hosted over `GET` and — when enabled — RFC `QUERY`. | `Documentation/guides/queries.md` |
| **One-shot query** | A request/response query (`QueryTransportType.REQUEST_RESPONSE`), as opposed to an observable one. | `Source/queries/QueryTransportType.kt` |
| **Observable query** | A query whose performer returns Kotlin `Flow` or JDK `Flow.Publisher`; KSP infers `QueryTransportType.OBSERVABLE`. Hosted as an HTTP snapshot, direct SSE or WebSocket, or through a multiplexed hub. | `Source/queries/ObservableQueryPipeline.kt`, `Documentation/reference/http-contract.md` |
| **Query performer** | `QueryPerformer` — the generated, reflection-free invoker for one query, carrying its `QueryDescriptor`. | `Source/queries/QueryPerformer.kt` |
| **Query request / context** | `QueryRequest` (transport-independent arguments, paging, sorting) and `QueryContext` (per-execution state including `correlationId`). Both may be declared as query parameters and are never client input. | `Source/queries/QueryRequest.kt`, `Source/queries/QueryContext.kt` |
| **Query result** | `QueryResult<TData>` — the envelope with `data`, `isReady`, `paging`, optional `changeSet`, and the same validation and exception fields as `CommandResult`. | `Source/results/QueryResult.kt` |
| **Query renderer** | `QueryRendererFor<T>` — an ordered transform applied to result values and paging before they leave the pipeline; `QueryableQueryRenderer` does in-memory paging and sorting over `Iterable`. | `Source/queries/QueryRenderers.kt` |
| **Read-model interceptor** | `InterceptReadModel<T>` — ordered per-model interception after rendering, for one-shot and observable results alike. | `Source/queries/ReadModelInterceptors.kt` |
| **Emission guard** | `GuardObservableQueryEmission` — rechecks a condition for every observable emission; a denial is terminal. | `Source/queries/ObservableQueryEmissionGuards.kt` |
| **Read model for command** | A read model resolved as a command handler parameter through `CanResolveReadModelForCommand` and `ReadModelForCommandOwnership` (`DECLARED` for JPA and Chronicle, `FALLBACK` for MongoDB). | `Source/queries/ReadModelForCommandResolvers.kt` |
| **Query health** | `QueryHealthTracker` — live observable connection and subscription health, exposed at `GET /.cratis/queries/health`. | `Source/queries/QueryHealth.kt` |
| **Transfer mode** | `ObservableQueryTransferMode` — `FULL` (`"full"`) sends whole snapshots, `DELTA` (`"delta"`) sends change sets after the first snapshot. Requested per subscription. | `Source/queries/ObservableQueryProtocol.kt` |
| **Change set** | `ChangeSet<T>` — the computed delta carried by a `DELTA` subscription, produced by `ChangeSetComputer`. | `Source/results/ChangeSet.kt` |
| **Hub** | A multiplexed observable-query connection: `/.cratis/queries/ws` for WebSocket, `/.cratis/queries/sse` plus `/subscribe` and `/unsubscribe` for SSE. Frames are `ObservableQueryHubMessage` values. | `Documentation/reference/http-contract.md` |
| **Subscription revision** | A positive, JavaScript-safe integer identifying a subscription generation, bounded by `ObservableQuerySubscriptionRevision.MAX_VALUE`. | `Source/queries/ObservableQueryProtocol.kt` |

## Concepts, validation, and JSON

| Term | Meaning here | Grounded in |
| --- | --- | --- |
| **Concept** | A strongly typed wrapper over one scalar — `ConceptAs<T>` with a `value()` member, shaped so a Java record satisfies it directly. Serialized as the underlying value, and erased to the mapped client type in proxies. | `Source/concepts/ConceptAs.kt` |
| **Arc enum** | An enum implementing `ArcEnum` with explicit integer wire values via `@ArcEnumValue`; `@Flags` marks a bit-field enum. | `Source/concepts/ArcEnum.kt` |
| **Command / query validator** | `CommandValidator<T>` and `QueryValidator` — host-neutral, application-registered validators run by `DefaultCommandValidationFilter` and `DefaultQueryValidationFilter`. | `Source/commands/CommandValidator.kt`, `Source/queries/QueryValidator.kt` |
| **Concept validator** | `ConceptValidator<TConcept>` — a reusable invariant for one concept type, applied across command and query graphs without each owner repeating the rule. | `Source/validation/ConceptValidator.kt` |
| **Validation result** | `ValidationResult` — `severity` (`ValidationResultSeverity`), `message`, `members`, optional `state`, and a `reason` from `ValidationResultReasons` (`rule`, `malformedRequest`, `dependencyUnavailable`, `validatorFailed`, `constraintViolation`, `concurrencyViolation`). | `Source/results/ValidationResult.kt` |
| **Severity threshold** | The maximum nonblocking severity, defaulting to errors only, raised by `@TreatWarningsAsErrors` or the `X-Allowed-Severity` request header. | `Source/artifacts/TreatWarningsAsErrors.kt`, `Documentation/reference/http-contract.md` |
| **Derived type** | A polymorphic JSON subtype registered by `@DerivedType(id)`, carried on the wire as `_derivedTypeId`. | `Source/polymorphism/DerivedType.kt` |
| **Arc Jackson module** | `ArcJacksonModule` plus `ArcPropertyNamingStrategy` — Arc's wire contract for concepts, Arc enums, temporal types, and derived types. Jackson is the only JSON library; Gson is prohibited. | `Source/json/ArcJacksonModule.kt`, `AGENTS.md` |

## Identity, authorization, and tenancy

| Term | Meaning here | Grounded in |
| --- | --- | --- |
| **Principal** | `ArcPrincipal` — immutable caller identity (`id`, `name`, `isAuthenticated`, `roles`, `claims`, `authenticationScheme`) captured once at transport entry by an `ArcPrincipalFactory`. | `Source/authorization/ArcPrincipal.kt` |
| **Authentication** | Arc's own ordered handler chain — `AuthenticationHandler` (coroutine) and `AsyncAuthenticationHandler` (Java `CompletionStage`) composed by `Authentication`. Failures are aggregated into one generic 401. | `Source/authentication/Authentication.kt` |
| **Authentication outcome** | `AuthenticationOutcome` — `Authenticated`, `Failed`, or `Anonymous`; an anonymous result lets later handlers try. | `Source/authentication/AuthenticationOutcome.kt` |
| **Authorization** | Metadata-driven access control from `@Authorize`, `@AllowAnonymous`, and repeatable `@Roles`, evaluated by `AuthorizationEvaluator` against named `AuthorizationPolicy` beans. It is not Spring Security configuration. | `Source/authorization/`, `Documentation/reference/annotations.md` |
| **Identity details provider** | `IdentityDetailsProvider<T>` (or Java `AsyncIdentityDetailsProvider<T>`) — supplies application-specific detail for `GET /.cratis/me`. At most one may be registered. | `Source/identity/IdentityDetailsProvider.kt` |
| **Identity cookie** | `.cratis-identity` (`IdentityConstants`) — the client-readable, Base64-encoded cache of the `/.cratis/me` response. Deliberately excluded from authentication input. | `Source/identity/IdentityConstants.kt`, `Documentation/reference/http-contract.md` |
| **Users / tenants providers** | `UsersProvider` and `TenantsProvider` (plus `Async*` Java forms) behind the anonymous development routes `/.cratis/users` and `/.cratis/tenants`. | `Source/identity/UsersProvider.kt`, `Source/tenancy/TenantsProvider.kt` |
| **Tenancy** | Explicit per-request tenant selection with no thread-local state: a `TenantResolutionContext` captured at entry, a `TenantIdResolver` chain (`CompositeTenantIdResolver` in list order), and `TenancyOptions` defaults. | `Source/tenancy/`, `Documentation/reference/configuration.md` |
| **Tenant access evaluator** | `TenantAccessEvaluator` — the Spring-side check that a resolved `TenantId` is allowed for the captured caller; denial is a generic 403. | `Integrations/SpringBoot/…/ArcTenantResolution.kt` |
| **Tenant namespace** | The resolved tenant value passed alongside `tenantId` to commands and every query transport, used by integrations to select a store. | `Documentation/reference/http-contract.md` |
| **Correlation** | The per-execution `correlationId`. It is a plain `java.util.UUID` — there is no concept type — carried on `CommandContext`, `QueryContext`, both result envelopes, and the `X-Correlation-ID` header (configurable via `cratis.arc.correlation-header`). | `Source/commands/CommandContext.kt`, `Documentation/reference/http-contract.md` |
| **Introspection** | The anonymous metadata routes `GET /.cratis/commands` and `GET /.cratis/queries`, served from `IntrospectionService`. | `Source/introspection/IntrospectionService.kt` |

## Hosting, Chronicle, and testing

| Term | Meaning here | Grounded in |
| --- | --- | --- |
| **Autoconfiguration** | A Spring Boot `@AutoConfiguration` class listed in a module's `AutoConfiguration.imports`; Arc registers four in the core starter. | `Integrations/SpringBoot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| **Application coroutine scope** | `ArcApplicationCoroutineScope` — the bounded, application-owned scope and dispatcher all Arc work runs in, sized by `coroutine-parallelism` and `coroutine-queue-capacity`. | `Integrations/SpringBoot/…/ArcApplicationCoroutineScope.kt` |
| **RFC QUERY** | The `QUERY` HTTP method Arc hosts alongside `GET` for structured query bodies, enabled by `cratis.arc.endpoints.enable-query-http-method`. Deliberately absent from OpenAPI. | `Documentation/reference/http-contract.md` |
| **Route prefix / segments to skip** | The conventional-route settings (`cratis.arc.endpoints.route-prefix`, `segments-to-skip-for-route`) that must match the proxy generator's `endpoints` configuration. | `Documentation/reference/configuration.md` |
| **Event type** | A Chronicle `@EventType` value a command may return; Arc stages and appends it against the command key. | `Documentation/guides/chronicle.md` |
| **Concurrency scope** | `EventsWithConcurrencyScopes` — ordered routed events plus exact per-source Chronicle concurrency rules for one atomic append. | `Integrations/Chronicle/…/EventsWithConcurrencyScopes.kt` |
| **Reactor** | A Chronicle component reacting to events. It may hand commands to Arc explicitly through `ChronicleCommandSideEffectHandler`; `@ExecuteCommandsAsSystem(roles = …)` grants exact roles. | `Integrations/Chronicle/…/ChronicleCommandSideEffects.kt` |
| **Scenario** | An in-process test of the real pipelines — `CommandScenario`, `QueryScenario`, `ObservableQueryScenario`, extended for Chronicle through a `CommandScenarioExtender` found by `ServiceLoader`. No kernel is started. | `Testing/src/main/kotlin/io/cratis/arc/testing/` |
| **`JavaAsyncScope`** | The Java-facing structured scope over a caller-supplied executor, exposing cancellable `CompletionStage` command, query, authentication, observable-query, and health facades. | `Source/java/AsyncPipelineFactories.kt` |
| **Contract test** | A fixture or gate in the unpublished `:ContractTests` module proving Kotlin, Java, generated-artifact, or TypeScript consumer contracts. | `ContractTests/README.md`, `settings.gradle.kts` |
| **Gate** | A CI-enforced check that must pass before work is done — full build, `apiCheck`, proxy determinism, TypeScript runtime, documentation verification. | `.github/workflows/build.yml`, [general.md](./general.md) |
