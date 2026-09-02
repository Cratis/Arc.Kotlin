---
title: Observe Arc execution
summary: Add Micrometer metrics and traces for Arc commands, queries, authentication, and identity details.
---

Add the Arc observability starter alongside the Arc Spring Boot starter:

```kotlin
dependencies {
    implementation("io.cratis:arc-observability-spring-boot-starter:<version>")
}
```

The starter activates when the application provides a Micrometer `ObservationRegistry`. Spring Boot Actuator supplies one in a typical metrics or tracing application. Without a registry, with `ObservationRegistry.NOOP`, or when `cratis.arc.observability.enabled=false`, Arc leaves the existing pipeline beans unchanged.

## Recorded operations

The starter decorates application-supplied or Arc-provided contracts without replacing their implementations:

| Observation | Operations | Low-cardinality tags |
| --- | --- | --- |
| `arc.command` | `execute`, `validate` | `arc.operation`, `arc.artifact`, `arc.outcome` |
| `arc.query` | `perform` | `arc.operation`, `arc.query`, `arc.outcome` |
| `arc.observable.query` | `open`, `subscription`, `emission` | `arc.operation`, `arc.query`, `arc.outcome` |
| `arc.authentication` | `authenticate` | `arc.operation`, `arc.outcome` |
| `arc.identity.details` | `provide` | `arc.operation`, `arc.outcome` |

Outcome values are bounded categories such as `success`, `invalid`, `unauthorized`, `not_ready`, `authenticated`, `anonymous`, `rejected`, `cancelled`, and `error`. Thrown failures are attached to the observation so a tracing handler can mark the span as failed. Observable subscriptions remain active until completion, cancellation, or failure; each delivered result has a separate emission observation.

Arc never records command values, query arguments, tenant identifiers, user identifiers, claims, headers, or cookies. Command artifact types and generated query names are the only model-specific low-cardinality dimensions.

## Correlation context

For command and query operations, Arc places the existing request correlation identifier in the observation context under `arc.correlation_id`. It is deliberately not a metric or span tag.

When SLF4J is available, the starter also places the value in MDC under `arc.correlation_id` for the duration of each coroutine resume. When the OpenTelemetry API is available, it places the same value in baggage. Both contexts are restored after execution, including cancellation and failure. This preserves structured coroutine propagation rather than relying on an unmanaged application `ThreadLocal`.

OpenTelemetry is optional. Add the tracing implementation appropriate for the application, for example Spring Boot Actuator with Micrometer's OpenTelemetry bridge. The Arc starter does not select an exporter.

## Configuration

```yaml
cratis:
  arc:
    observability:
      enabled: true
      correlation-baggage-enabled: true
      correlation-logging-enabled: true
```

The properties are ordinary JavaBean configuration through `ArcObservabilityProperties`. Disable baggage or logging correlation independently when the host owns those contexts itself.
