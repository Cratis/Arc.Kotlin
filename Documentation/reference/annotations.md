---
title: Annotation reference
description: Exact Arc annotations available to Kotlin and Java application models.
---

## Artifact annotations

| Annotation | Target | Contract |
| --- | --- | --- |
| `@Command` | Class | Marks a model-bound command with an instance `handle` method. |
| `@ReadModel` | Class | Marks a model whose static or companion methods are queries. |
| `@CommandKey` | Property, field, value parameter, getter | Selects the command identity. Required for plain Chronicle event responses. |
| `@FromServices` | Value parameter | Resolves a query parameter from the host service container instead of the request. Command handler dependencies are service-resolved by their generated signature. |
| `@TreatWarningsAsErrors` | Class, function | Makes information and warning validation feedback blocking by default. |

## Validation annotations

| Annotation | Target | Contract |
| --- | --- | --- |
| `@Phone` | Field, property getter, value parameter, annotation class | Accepts empty values or strings containing only ASCII digits, JavaScript whitespace, parentheses, plus, and hyphen; use a presence constraint when empty is invalid. |
| `@Url` | Field, property getter, value parameter, annotation class | Accepts empty values or strings beginning with `http://` or `https://` and a nonempty value after the scheme; use a presence constraint when empty is invalid. |
| `@CreditCard` | Field, property getter, value parameter, annotation class | Accepts empty values or Luhn-valid ASCII card numbers containing optional spaces or hyphens; use a presence constraint when empty is invalid. |

All three are Jakarta constraints, work from Kotlin and Java, and remain in generated validation metadata. `@Phone` and `@Url` also emit matching TypeScript runtime rules. `@CreditCard` is server-only because the pinned `@cratis/arc` client runtime has no compatible credit-card rule; emitting the current .NET extractor shape would make the proxy uncompilable. Hibernate Validator `@URL` follows the same client mapping as `@Url` when present at compile time, while `@CreditCardNumber` has the same server-only boundary as `@CreditCard`. `null` is valid, so nullability remains the responsibility of `@NotNull`.

## Routing and transport annotations

| Annotation | Target | Values and behavior |
| --- | --- | --- |
| `@Path(value)` | Class, function | Overrides query path metadata. Explicit query paths are preserved verbatim. |
| `@QueryHttpMethod(value)` | Read-model class, function | Proxy preference: `AUTO` (default), `GET`, or `QUERY`. A class value defaults every query; a method value overrides it. |
| `@QueryTransport(value)` | Function | `REQUEST_RESPONSE` (default) or `OBSERVABLE`. KSP infers `OBSERVABLE` for Kotlin `Flow` and JDK `Flow.Publisher`; Spring hosts observable HTTP snapshots, direct SSE/WebSocket, and multiplexed hubs. |

## Authorization annotations

| Annotation | Target | Values and behavior |
| --- | --- | --- |
| `@AllowAnonymous` | Class, function | Allows unauthenticated access. |
| `@Authorize` | Class, function | Optional `policy`, `roles`, and `schemes` arrays. Requires an authenticated caller. |
| `@Roles(vararg value)` | Class, function | Repeatable declaration requiring at least one named role. |
| `@RolesContainer(value)` | Class, function | JVM container generated for repeated `@Roles`; application code normally does not use it directly. |

## Serialization annotation

| Annotation | Target | Contract |
| --- | --- | --- |
| `@DerivedType(id)` | Class | Adds `_derivedTypeId` and registers a stable identifier for polymorphic Arc JSON. The identifier must be nonblank and unique for its base type. |
