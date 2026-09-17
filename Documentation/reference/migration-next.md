---
title: Migration guide — next release
description: Breaking changes, required actions, and new capabilities for consumers upgrading from Arc for Kotlin/Java 7.2.0 to the next release.
---

## Overview

This page covers upgrading from 7.2.0, the most recent release, to the next one. It lists every breaking change, the exact symptom a consumer will see, and the action required to resolve it. Read the breaking-changes section first; each fix is independent. New capabilities follow.

---

## Breaking changes

### Manifest format 8 — regenerate producers and consumers in lockstep

**What changed.** The Arc artifact manifest format advanced from 7 to 8. Format 8 adds an explicit `ignoreValidation` boolean on every property descriptor. The Gradle plugin reader rejects any manifest that is not exactly format 8.

**Symptom.** Running `generateArcProxies` (or any task that reads the classpath manifest) fails with a Gradle build error:

```text
Unsupported Arc artifact manifest format 7 in <path-to-jar>!/META-INF/cratis/arc/<module>.json; expected 8.
```

A missing or non-numeric `formatVersion` field produces:

```text
Arc artifact manifest in <path> must declare an explicit numeric formatVersion=8.
```

**Action.** Rebuild every module that contributes an Arc manifest — both the producer (the module that defines `@Command` / `@ReadModel` types) and every consumer (the module that calls `generateArcProxies` against those types) — with the current Arc KSP processor. A classpath that mixes manifests from the old and new processor will fail at proxy-generation time. All modules in a multi-module build must be rebuilt together before a fresh proxy-generation run.

---

### Chronicle integration requires kernel 18.4.0 or newer

**What changed.** Chronicle SDK 5.1.0 is the integration baseline. Kernels 18.3.1 and earlier omit the `IsAuthorized` field from gRPC responses whenever the result _was_ authorized, because proto3 does not serialize the default value (`false`). A JVM proto3 client decodes the absent field as `false`, so every authorized command append or namespace provision looks like a denial.

**Symptom.** Connecting `arc-chronicle-spring-boot-starter` to a kernel older than 18.4.0 causes event-store provisioning to fail at startup with an application-level assertion:

```text
Could not provision event store '<name>' (authorized=false, authorizationFailureReason=''): []
```

Commands that return events then fail before any append is attempted.

**Action.** Upgrade the Chronicle kernel to 18.4.0 or newer. The Arc integration pins `cratis/chronicle:18.4.0-development` (OCI digest `sha256:0437a1a60e237b104b747eea94a57a947690e0abaff5a719212d095c0787517c`) for its own contract gate. No application code change is required once the kernel is current.

---

### Startup failure when build-time and runtime route settings disagree

**What changed.** The Arc Gradle plugin now writes `META-INF/arc/endpoint-options.json` into the compiled application JAR recording the route settings used during proxy generation (`routePrefix`, `segmentsToSkipForRoute`, `includeCommandNameInRoute`, `includeQueryNameInRoute`, `enableQueryHttpMethod`). The Spring Boot starter reads this resource at startup and compares it against the resolved `cratis.arc.endpoints.*` runtime properties.

**Symptom.** If any setting disagrees, the application context fails to start with an `IllegalStateException`:

```text
Arc endpoint-options mismatch: the Gradle plugin generated proxies with different route settings than the runtime configuration.
Generated clients will call URLs the server does not serve. Align 'cratisArc.endpoints.*' (build) with 'cratis.arc.endpoints.*' (runtime).
Mismatched settings:
  - segmentsToSkipForRoute: build-time=4, runtime=6
Fix: update either the Gradle 'cratisArc.endpoints' block or the 'cratis.arc.endpoints' properties so both sides agree.
```

**Why this matters.** Applications where the Gradle `endpoints.segmentsToSkip` or `endpoints.routePrefix` was set differently from the corresponding `application.properties` keys were silently producing proxies whose generated URLs did not match the routes the server registered. This startup failure surfaces a latent bug that would otherwise appear only as a 404 in the browser. Applications that have always kept the two sides consistent are unaffected.

**Action.** Compare the Gradle build file and `application.properties`:

```kotlin
// build.gradle.kts
cratisArc {
    endpoints {
        segmentsToSkip.set(4)           // build-time value
        enableQueryHttpMethod.set(true)
    }
    proxies {
        segmentsToSkip.set(4)           // must equal endpoints.segmentsToSkip
    }
}
```

```properties
# application.properties
cratis.arc.endpoints.segments-to-skip-for-route=4
cratis.arc.endpoints.enable-query-http-method=true
```

Every setting in `cratisArc.endpoints` must match the corresponding `cratis.arc.endpoints.*` property. Applications with no `cratisArc.endpoints` configuration and no `cratis.arc.endpoints.*` properties are not affected — an absent resource is silently ignored.

---

### Query renderer ownership — automatic iterable renderer no longer restores filtered rows

**What changed.** The automatic iterable renderer is now a fallback only when no configured renderer matches the original query result. In earlier releases, the automatic renderer would run after a configured renderer and could silently restore the original rows or replace provider-owned paging with an in-memory recalculation.

**Symptom.** An application with a `QueryRendererFor<T>` bean that filters rows or supplies custom paging will now serve the renderer's output unchanged. If the renderer previously relied on the automatic renderer running afterward to re-sort, re-page, or re-count from the full original set, the response will no longer include that automatic post-processing.

**Action.** Applications that registered `QueryRendererFor<T>` and expected the framework to apply in-memory sorting and paging on top of the renderer's output must now do so explicitly inside the renderer, or supply a second explicitly ordered `QueryableQueryRenderer` stage. Renderers that return the full unfiltered set and depended on paging being applied afterward need no change — the automatic iterable renderer still fires as the final fallback for that case.

---

### Query sorting helpers now derived from returned row fields

**What changed.** Generated TypeScript sorting-helper types (`<Query>SortBy`, `<Query>SortByWithoutQuery`) and their constructor arguments now use the fields of the _return_ model, not the names of the query's request parameters.

**Symptom.** TypeScript code that constructs a sorting helper using a request-parameter name that does not exist on the return model will fail to compile after regenerating proxies. The generated helper type will no longer contain a constructor argument for that name.

**Action.** Regenerate proxies and update TypeScript call sites to use the return-model property names. If a request parameter name coincidentally matched a return-model field name, no change is required. Sorting capability flags (`supportsSorting`, `supportsPaging`) remain unchanged.

---

### Identity details discovery from provider declarations

**What changed.** Arc KSP now automatically discovers identity-details roots from public concrete source provider classes and typed factory return types. Previously, types not annotated with `@ExportedType` and not reachable through a `@Command` or `@ReadModel` were not processed.

**Symptom.** A module that previously relied on the absence of automatic discovery — for example, one that expected a provider type to be excluded from the generated artifact module — will now find that type included. KSP diagnostics for unsupported or erased provider boundaries (`ARCKSP0307`) may appear on types that were previously silently ignored.

**Action.** Review any identity-details provider type that should not be an artifact root, and annotate it or restructure the binding so that KSP does not reach it through a public concrete declaration. Erased, wildcard, unspecialized, inaccessible, or unsupported boundaries still fail with `ARCKSP0307`; supported boundaries that were previously invisible will now be processed correctly.

---

## New capabilities

### `@IgnoreValidation` — cut a member validation edge

Annotate a property with `@IgnoreValidation` to prevent Arc's validation pipeline from reaching that member during Jakarta Bean Validation execution. The annotation cuts the logical member edge before getter or extractor access; it suppresses direct constraint execution and descendant recursive validation for that member, without affecting wire serialization, binding, or fluent-authored fingerprints.

```kotlin
import io.cratis.arc.artifacts.Command
import io.cratis.arc.validation.IgnoreValidation

@Command
data class UpdateProfile(
    val displayName: String,
    @IgnoreValidation val internalTag: String
)
```

Manifest format 8 carries the ignore flag explicitly. A manifest produced before format 8 that contains an ignored property will be rejected by the current Gradle plugin reader. See [Shared fluent validation](validation.md#ignore-a-validation-member-edge) for the bounded contract.

---

### Hibernate `@Range` and `@Length`, and Jakarta `@Digits` generate client validation rules

Three additional Jakarta/Hibernate constraint annotations now produce client-side validation rules in generated TypeScript:

| Annotation | Maps to client rule |
| --- | --- |
| `org.hibernate.validator.constraints.Range` | `greaterThanOrEqual` and `lessThanOrEqual` (numeric fields) |
| `org.hibernate.validator.constraints.Length` | `minLength`, `maxLength`, or `length` (string/collection fields) |
| `jakarta.validation.constraints.Digits` | `matches` with a decimal-digit regular expression |

These behave the same as the existing Jakarta annotations they most closely resemble (`@Min`/`@Max` for `@Range`, `@Size` for `@Length`). Constraints that are not representable as a JavaScript-safe number fail code generation with `ARCKSP0301` rather than being silently dropped.

---

### Annotation and DSL validation rules union, not override

When a property carries both a Jakarta annotation constraint and a `FluentModelValidator<T>` DSL rule for the same member, Arc now applies both as a union rather than using precedence. This matches the JVM runtime behavior, where Jakarta validation and the fluent validator both execute server-side. Generated TypeScript validators reflect the combined constraint set.

See [Shared fluent validation — conjunction, duplication and contradictions](validation.md#conjunction-duplication-and-contradictions) for the full contract.

---

### Optional `arc-rxjava3` artifact for RxJava 3 observable queries

The new optional artifact `io.cratis:arc-rxjava3` adds support for RxJava 3 return types in observable query methods: `Observable<T>`, `ObservableSource<T>`, and `Subject<T>`. Add the artifact when your observable query methods return RxJava 3 types:

```kotlin
dependencies {
    implementation("io.cratis:arc-rxjava3:<version>")
}
```

Without this dependency, `Observable` return types are not recognized and KSP will not generate an observable performer for them. Kotlin `Flow` and JDK `Flow.Publisher` remain the primary types and require no additional dependency. See [Declare observable queries](../guides/observable-queries.md#rxjava-3-option) for usage.

---

### Read-model naming policy for MongoDB

`NamingPolicy` and its default implementation `DefaultNamingPolicy` control how Arc maps a read-model JVM type to a MongoDB collection name. The default pluralises the simple class name using Evo Inflector. Where Evo Inflector and the Chronicle Kernel's Humanizer library disagree on a plural, the kernel is authoritative; override the naming policy for that type:

```kotlin
@Configuration
class NamingConfig {
    @Bean
    fun namingPolicy(): NamingPolicy = object : NamingPolicy {
        override fun getReadModelName(readModelType: Class<*>): String =
            when (readModelType) {
                PersonView::class.java -> "People"   // kernel writes "People", inflector writes "Persons"
                else -> DefaultNamingPolicy().getReadModelName(readModelType)
            }
    }
}
```

See [Read-model naming](../guides/read-model-naming.md) for the full policy contract.

---

### Ambient tenancy — tenant context in coroutines and blocking Java

`withTenant` / `currentTenant()` propagate the current `TenantId` through Kotlin coroutines across dispatcher switches. `TenantContextBridge` provides the same capability for blocking Java call paths. See [Ambient tenancy](../guides/ambient-tenancy.md).

---

### `TenantContextMongoAccess` — tenant-bound MongoDB operations

The MongoDB integration registers a `TenantContextMongoAccess` bean when a single `TenantAwareMongoOperationsResolver` is present. It resolves tenant-bound `MongoOperations` using the ambient `TenantId` from the coroutine context, so infrastructure that does not hold an explicit tenant reference can still obtain correctly scoped MongoDB access.

---

### MongoDB and JPA observe extensions

`MongoObservation` (MongoDB integration) exposes `observe`, `observeList`, `observeSingle`, `observeById`, `observeShared`, and `observePublisher` extension helpers that back change-stream-based observable queries with a reconnecting cold `Flow`. The JPA integration exposes equivalent helpers for in-process notification-driven observable reads. Both are described in [Spring Data read models](../guides/spring-data.md).

---

## Known limitations

- **CodeQL** currently analyses Java source only (tracked in [#195](https://github.com/Cratis/Arc.Kotlin/issues/195)); Kotlin source is not covered.
- **.NET parity** remains `Partial`; see the [feature parity matrix](parity.md) for the boundaries of each row.
- **Checked empty-history concurrency** is blocked on an upstream Chronicle change ([#176](https://github.com/Cratis/Arc.Kotlin/issues/176)).
- `shouldBeInvalid` in test scenarios now rejects failures caused by dependency-only validation beans; scenarios that previously passed because a dependency validator produced an error will now require the target validator to produce that error directly.
