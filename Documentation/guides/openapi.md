---
title: Publish an OpenAPI document
description: Add the Arc OpenAPI Spring Boot starter and serve a generated OpenAPI 3.1 document.
---

## Add the starter

Add the OpenAPI starter to a Spring Boot application that uses generated Arc artifact modules.

```kotlin
dependencies {
    implementation("io.cratis:arc-openapi-spring-boot-starter:<version>")
}
```

The starter activates only in a servlet web application with Arc's generated modules. It uses `swagger-models` for the public document model and does not require springdoc.

## Read the document

The same cached JSON document is available from both routes:

- `/v3/api-docs`
- `/.cratis/openapi.json`

Arc generates and serializes the OpenAPI 3.1 document once during application startup. Applications can inject `io.swagger.v3.oas.models.OpenAPI` or `ArcOpenApiDocument`; the latter also exposes a defensive copy of the cached JSON bytes.

## Understand the generated contract

The document follows the routes calculated by `cratis.arc.endpoints`, including the route prefix, skipped package segments, name inclusion, namespace conflicts, and query `@Path` overrides.

| Arc artifact | OpenAPI operation |
| --- | --- |
| Command | `POST` on the command route |
| Command validation | `POST` on `<command-route>/validate` |
| Query | `GET` on the query route |
| Identity details schema | `GET /.cratis/identity-details/schema` |
| Current identity, when an identity provider exists | `GET /.cratis/me` |

The optional RFC `QUERY` runtime method is deliberately omitted because OpenAPI Path Items do not define it.

Generated components cover command payloads, client-supplied query parameters, model and enum metadata, nullable and collection types, and Arc's `CommandResult`, `QueryResult`, `ValidationResult`, `PagingInfo`, and `ChangeSet` envelopes. Service, `QueryRequest`, and `QueryContext` parameters are runtime infrastructure and are omitted from OpenAPI. Enum schemas use their numeric Arc wire values and expose names through `x-enumNames`.

Each discovered Kotlin or Java `ConceptAs<T>` receives a reusable component schema for its underlying wire value rather than an object wrapper. Direct and concept-backed `UUID`, `LocalDate`, `LocalTime`, and `Duration` values remain `type: string` with `format: uuid`, `format: date`, `format: time`, and `format: duration`, respectively. Arc disables Jackson's `WRITE_DURATIONS_AS_TIMESTAMPS` in both Core and Spring, so the documented duration shape matches its ISO-8601 JSON string and generated TypeScript `string`; it is not Fundamentals `TimeSpan` because the Java and C# wire formats differ. Generated TypeScript clients use `Guid`, `DateOnly`, and `TimeOnly`, but those classes do not change the OpenAPI wire shapes. Other string and integer concepts retain their corresponding scalars; enum concepts retain the underlying numeric values and `x-enumNames`. Command and model properties, query parameters, collection items, query data, and command responses reference those scalar concept schemas. Imperative `ConceptValidator` implementations remain runtime behavior and are not translated into OpenAPI keywords. The current OpenAPI generator documents the concept wire shape, not executable concept-validation rules.

Secured operations reference the `Bearer` HTTP JWT scheme. Generated authorization metadata is preserved through `x-roles`, `x-policy`, and `x-authenticationSchemes` operation extensions.

## Override document ownership

Arc backs off when the application supplies an `OpenAPI` bean or an `ArcOpenApiDocument` bean. An application handler on either document route also wins because Arc's fallback handler mapping runs after application request mappings.
