---
title: Configuration reference
description: Arc Spring Boot properties, Gradle plugin extension fields, defaults, coordinates, and tasks.
---

## Published identities

| Component | Identity |
| --- | --- |
| Runtime | `io.cratis:arc:<version>` |
| KSP processor | `io.cratis:arc-ksp:<version>` |
| Spring Boot integration | `io.cratis:arc-spring-boot-starter:<version>` |
| Spring Data JPA | `io.cratis:arc-spring-data-jpa:<version>` |
| Spring Data MongoDB | `io.cratis:arc-spring-data-mongodb:<version>` |
| OpenAPI | `io.cratis:arc-openapi-spring-boot-starter:<version>` |
| Observability | `io.cratis:arc-observability-spring-boot-starter:<version>` |
| Chronicle integration | `io.cratis:arc-chronicle-spring-boot-starter:<version>` |
| Testing | `io.cratis:arc-testing:<version>` |
| Gradle plugin | Plugin ID `io.cratis.arc`; implementation artifact `io.cratis:arc-gradle-plugin:<version>` |

Local builds default to `0.0.0-SNAPSHOT`; `-Pversion=<version>` overrides it. A local coordinate is not automatically available in a consumer build until it is published or supplied by a composite build.

## Host-neutral tenancy options

`Source` exposes immutable `TenancyOptions` for request adapters and tests. The defaults match Arc on .NET:

| Option | Default | Meaning |
| --- | --- | --- |
| `headerName` | `x-cratis-tenant-id` | Case-insensitive request header used by header resolution and subdomain fallback. |
| `queryParameterName` | `tenantId` | Case-sensitive query parameter. |
| `claimType` | `tenant_id` | Claim read from explicit context claims, then principal claims. |
| `baseDomain` | Empty | Required by `SubdomainTenantIdResolver`; must contain at least two valid DNS labels and cannot be an address. |
| `fixedTenantId` | `development` | Value returned by fixed and development resolvers. |

Every resolver receives a `TenantResolutionContext` containing explicit headers, query parameters, host, claims, and principal. No resolver reads thread-local or ambient request state. `CompositeTenantIdResolver` tries resolvers in list order and returns the first nonblank result, so declaration order is the complete precedence rule.

`SubdomainTenantIdResolver` accepts exactly one DNS label before `baseDomain` and otherwise falls back to `headerName`. For example, `acme.myapp.com` resolves `acme` for `myapp.com`; `myapp.com`, `one.two.myapp.com`, unrelated hosts, invalid labels, and address literals use the fallback.

## Spring Boot properties

All properties use the `cratis.arc` prefix.

| Property | Default | Meaning |
| --- | --- | --- |
| `endpoints.route-prefix` | `api` | Prefix for conventional routes. |
| `endpoints.segments-to-skip-for-route` | `0` | Leading package segments omitted from routes. Must be nonnegative. |
| `endpoints.include-command-name-in-route` | `true` | Includes command names in conventional routes. |
| `endpoints.include-query-name-in-route` | `true` | Includes query names in conventional routes. |
| `endpoints.enable-query-http-method` | `true` | Adds RFC QUERY alongside GET for one-shot and observable snapshot routes. |
| `correlation-header` | `X-Correlation-ID` | Java-friendly request and response header name used consistently by command and query transports. Must not be blank. |
| `tenant-header` | `x-cratis-tenant-id` | Backward-compatible alias and default for `tenancy.header-name`. |
| `tenancy.resolvers` | `header` | Ordered resolver precedence. Values are `fixed`, `header`, `query`, `claim`, `subdomain`, and `development`. |
| `tenancy.required` | `false` | Rejects unresolved command, query, observable, and identity requests with 400 when enabled. |
| `tenancy.header-name` | Unset | Case-insensitive header name; an unset value uses `tenant-header`. |
| `tenancy.query-parameter-name` | `tenantId` | Exact case-sensitive query parameter name. |
| `tenancy.claim-type` | `tenant_id` | Principal claim used for resolution and authenticated membership checks. |
| `tenancy.base-domain` | Empty | Valid multi-label DNS base domain required by subdomain resolution. |
| `tenancy.fixed-tenant-id` | `development` | Value returned by fixed and development resolution. |
| `tenancy.constrain-to-authenticated-claims` | `true` | When authenticated claims of `claim-type` exist, the resolved tenant must be one of them. |
| `spring-data.jpa.command-transactions-enabled` | `false` | Opts a verified fixed JPA persistence unit into an imperative, thread-bound command transaction scope. Dynamic tenant units are never auto-enrolled. |
| `spring-data.mongodb.command-transactions-enabled` | `false` | Opts a verified fixed MongoDB template with an identity-aligned manager into an imperative, thread-bound command transaction scope. Dynamic tenant stores are never auto-enrolled. |
| `request-timeout` | `30s` | Async servlet request and bounded first-result ceiling. |
| `coroutine-parallelism` | `4` | Maximum actively executing Arc request coroutines; must be greater than zero. |
| `coroutine-queue-capacity` | `256` | Maximum admitted Arc operations waiting for execution; zero permits no waiting. |
| `overload-retry-after-seconds` | `5` | `Retry-After` value when general Arc request admission is exhausted. |
| `maximum-request-body-bytes` | `1048576` | Maximum command or QUERY body, enforced for declared and streamed lengths. |
| `command-scope-completion-timeout` | `5s` | Independent cooperative timeout for each best-effort command execution-scope completion. |
| `identity-cookie-secure-policy` | `auto` | `always`, `never`, or `auto`; auto secures HTTPS and every non-development profile. |
| `expose-exception-details` | Profile-derived | When unset, enabled only for `dev`, `development`, or `local` profiles. |
| `observable-queries.wait-for-first-result-timeout` | `30s` | Maximum default wait for the first HTTP snapshot result. A request may shorten it. |
| `observable-queries.keep-alive-interval` | `30s` | Idle interval between hub `Ping` messages; zero disables heartbeats. |
| `observable-queries.connection-timeout` | `30m` | Maximum SSE connection lifetime; zero disables the transport timeout. |
| `observable-queries.maximum-connections` | `1000` | Concurrent direct and multiplexed streaming connections. Exhaustion returns 503. |
| `observable-queries.maximum-subscriptions-per-connection` | `100` | Active subscriptions on one hub connection. Exhaustion returns 429 for SSE POST. |
| `observable-queries.outbound-buffer-capacity` | `64` | Pending outbound frames per connection before fail-closed cleanup. |
| `observable-queries.maximum-inbound-message-size` | `65536` | Maximum WebSocket text message size in bytes. |
| `observable-queries.overload-retry-after-seconds` | `5` | `Retry-After` value on 503/429 overload responses. |
| `observable-queries.web-socket-enabled` | `true` | Registers WebSocket routes when Spring WebSocket is on the classpath. |

The optional observability starter adds properties under `cratis.arc.observability`:

| Property | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Decorates Arc execution contracts when a non-no-op `ObservationRegistry` bean exists. |
| `correlation-baggage-enabled` | `true` | Places command and query correlation identifiers in OpenTelemetry baggage when its API is available. |
| `correlation-logging-enabled` | `true` | Places command and query correlation identifiers in SLF4J MDC when its API is available. |

Correlation identifiers are observation context, baggage, and logging values, not metric or span tags. The starter never records tenant identifiers, user identifiers, command values, query arguments, claims, headers, or cookies.

## Gradle plugin extension

`cratisArc` fields:

| Field | Default | Meaning |
| --- | --- | --- |
| `moduleName` | Project name | Stable KSP artifact module name; cannot be blank. |
| `dependencyVersion` | Plugin implementation version | Version for managed `arc` and `arc-ksp` dependencies. |
| `manageDependencies` | `true` | Adds missing runtime and KSP dependencies. |
| `endpoints.routePrefix` | `api` | Proxy route prefix. |
| `endpoints.segmentsToSkip` | `0` | Package segments omitted from proxy routes. |
| `endpoints.includeCommandNames` | `true` | Includes command names. |
| `endpoints.includeQueryNames` | `true` | Includes query names. |
| `endpoints.enableQueryHttpMethod` | `true` | Permits QUERY selection in proxies. |
| `proxies.enabled` | `true` | Enables generation when an output directory exists. |
| `proxies.outputDirectory` | Unset | Generated TypeScript directory. An unset value skips generation. |
| `proxies.removeStaleGeneratedFiles` | `true` | Removes only stale Arc-marked files. |
| `proxies.segmentsToSkip` | `0` | Package segments omitted from generated import layout. |

Resolver configuration is validated at startup: the resolver list cannot be empty or contain duplicates, selected resolver options cannot be blank, fixed/development identifiers must be nonblank, and subdomain configuration must have a valid base domain. `CompositeTenantIdResolver` follows the listed order exactly. An application-defined `TenantIdResolver` or `TenantAccessEvaluator` bean replaces the default with Spring Boot's normal `@ConditionalOnMissingBean` behavior.

The host captures case-insensitive headers, each query parameter's first value, host, principal, and claims into an explicit `TenantResolutionContext` at request or subscription entry. Resolution is performed once and the resulting value is supplied as both `tenantId` and `tenantNamespace`; no thread-local state is used. Optional unresolved tenancy remains `null` for backward compatibility. Fixed or development resolution must be configured explicitly before either can supply a default. Access denial is generic and returns 403.

The plugin applies Kotlin/JVM and KSP `2.1.0-1.0.29`, targets JDK 17, and treats Kotlin and Java warnings as errors. `generateArcProxies` depends on main classes, KSP, and resources, and `build` depends on it. Use the standard active JDK selection through `JAVA_HOME` and `PATH`; the repository requires no machine-specific absolute JDK path.

KSP diagnostics use stable `ARCKSP` codes. Configuration, command, query, proxy-shape, validation-metadata, enum-wire-value, and interoperability diagnostics are cataloged in `CodeGeneration/KSP/DIAGNOSTICS.md`; errors stop generation, while warnings identify risky but compilable conventions.

Published Kotlin APIs use checked `.api` baselines. The root `apiCheck` gate covers `arc`, KSP, the Gradle plugin implementation artifact, every published integration, and testing support; contract tests and runnable samples are intentionally unpublished and excluded. An API change therefore requires an explicit baseline update rather than silently changing compiler or plugin contracts. The temporal/UUID proxy slice changes generated TypeScript source types. Recursive shape metadata moves artifacts to manifest format 5 and adds immutable public JVM shape descriptors while preserving legacy constructor descriptors and compatibility getters.

The runtime proxy harness has five wired unit tests plus 25 behavioral runtime tests. It runs 15 general tests in a UTC Node process, then five calendar tests in a separate UTC process and the same five in a separate `America/Los_Angeles` process. Counts are parsed from each TAP summary, which must report its exact expected test/pass total and zero fail, cancelled, skipped, or todo results. Spring process-spawn errors fail cleanly instead of hanging startup.

Repository proxy verification has three distinct gates: deterministic generation plus strict TypeScript compilation with `verbatimModuleSyntax`, comparison with a repository-local semantically normalized .NET-derived expected fixture intended for source control, and `:ContractTests:typeScriptRuntimeTest` against the executable Kotlin Spring Boot sample. The differential's exact path and body comparison occurs after CRLF-to-LF and generated-header normalization, the expected fixture's capture-time namespace and query-name casing transformations, and expected-side .NET import rewrites required by `verbatimModuleSyntax`. `SetCommandValues`, `ClearCommandValues`, and query helper types such as `PerformQuery`, `SetSorting`, `SetPage`, `SetPageSize`, and `ChangeSet` become type-only imports. One additional expected-side correction at `Commands/CreateFixtures.ts` changes `Command<ICreateFixtures, FixtureModel>` to `Command<ICreateFixtures, FixtureModel[]>`. Current .NET output already calls `super(FixtureModel, true)`, so its scalar generic contradicts enumerable runtime behavior. The .NET-derived `FixtureModel.labelsByCategory` capture already uses `@field(Object)` and `Record<string, string>`; no dictionary normalization is applied to it, so that exact declaration is compared after only the documented line-ending/header and import normalization. It proves that one string-key/string-value Record fixture, not non-string keys, nullable entries, typed model values, `ValueMap`, or broader dictionary parity. No normalization transforms JVM output. The current .NET fixture contains no `Guid`, `DateOnly`, or `TimeOnly`, so focused generator and contract tests cover that mapping. See [Generate TypeScript proxies](../guides/typescript-proxies.md) for this boundary and the remaining capture-time reproducibility limitation.

## Manual KSP setup

Without the plugin, apply Kotlin/JVM and `com.google.devtools.ksp`, add `io.cratis:arc` to `implementation`, add `io.cratis:arc-ksp` to `ksp`, and set `arc.moduleName` in the KSP extension. Kotlin Spring applications also apply Kotlin's Spring plugin. Add the Arc Spring starter and `spring-boot-starter-web` for HTTP hosting.
