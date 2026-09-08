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

## Serialization annotations

| Annotation | Target | Contract |
| --- | --- | --- |
| `@DerivedType(id)` | Class | Adds `_derivedTypeId` and registers a stable identifier for polymorphic Arc JSON. The identifier must be nonblank and unique for its base type. |
| `@Flags` | Class | Marks an enum as a bit field. Generated TypeScript gains an `all<Name>` constant combining every nonzero member. Nothing about JVM serialization changes. |
| `@ArcEnumValue(value)` | Field | Declares an enum member's integer wire value where KSP cannot prove it from a single integer-literal constructor argument. |

Arc writes an enum as an integer: the result of `value()` when the enum implements `ArcEnum`, and the ordinal otherwise. Reading accepts that integer or the member name matched case-insensitively, and rejects anything else.

### Polymorphic property declarations

Declare polymorphic properties as an interface or abstract base, not an ordinary concrete base class. KSP reports `ARCKSP0305` when a collected command, read-model, interface, or reachable DTO property declares a concrete class with a distinct, nonabstract `@DerivedType` descendant visible in the current compilation. The check also applies to nullable properties and the element type of supported collections and arrays. Annotating the concrete base itself with `@DerivedType` does not exempt it when it has descendants.

A concrete leaf remains legal, as does ordinary inheritance without annotated descendants. Visiting a superclass to collect inheritance metadata is not itself a prohibited property use. Existing map restrictions are unchanged.

This authoring restriction prevents a concrete base instance from writing JSON without `_derivedTypeId` that a registered polymorphic base requires on read. It is a property-use check, not a guarantee for every runtime registry configuration: changing a declaration to an interface or abstract base does not validate arbitrary multilevel registrations. Root read-model types are not rejected solely for being concrete polymorphic bases. KSP checks source descendants, including those generated in later processing rounds and source descendants of dependency bases; binary-only descendants or manually registered types not visible to KSP are outside this check. Verify those runtime configurations separately.

### Runtime derived-type dispatch

Populate the `DerivedTypeRegistry` before reading registered base types with `ArcObjectMapper`. Runtime dispatch resolves `_derivedTypeId` only within the declared base type's registrations; it does not infer self registrations, search other bases, or follow a transitive chain of identifiers. A non-null value must be an object with a textual, known identifier whose registered target is assignable to that base. Explicit JSON `null` remains `null`.

Manual runtime registrations support multilevel inheritance. For an abstract `Root`, an annotated concrete `Middle` extending `Root`, and an annotated `Leaf` extending `Middle`, register each intended pair explicitly: `Root` → `Middle`, `Root` → `Leaf`, and `Middle` → `Leaf`. Reading `Root` with the middle identifier creates exactly `Middle`, even though `Middle` is also a registered base. Reading either `Root` or `Middle` with the leaf identifier creates `Leaf`. Reading `Middle` with its own identifier still fails unless `Middle` → `Middle` was explicitly registered.

Arc consumes only the current object's discriminator before binding the selected target. Nested properties and collection elements independently validate their identifiers against their own declared bases. Generic type bindings and property-specific Jackson bean configuration are retained during target binding. These are runtime mapper capabilities, not changes to KSP model authoring: the `ARCKSP0305` concrete-property restriction above and existing generic model restrictions remain in force.

The wire format is unchanged: annotated objects carry one `_derivedTypeId` alongside their ordinary properties. Updating readers retain replacement-read semantics rather than mutating an existing value, and strict Jackson merge configuration rejects those updates. Do not combine a registered Arc base with native Jackson type metadata such as `@JsonTypeInfo` or default typing: the typed deserialization entry rejects that configuration instead of allowing a separate subtype resolver to bypass Arc registry validation.

### Flags combinations

`@Flags` does not give an enum the ability to carry a combination. A JVM enum constant is one named value, so `Read or Write` has a constant to deserialize into only when the enum declares one. A generated client can compose such a value — the emitted `all<Name>` constant exists to be combined with `|` — and the server answers with the ordinary safe `malformedRequest` envelope on both a command body and a query argument, without disclosing the enum type. Arc .NET draws the same boundary, because its enum converter gates reads on whether the integer is a defined member.

Two shapes carry a combination:

- Declare the combination as its own member, which gives it a value to write and a constant to read into.
- Declare a set of the enum, which writes an array of member wire values and accepts any combination:

  ```kotlin
  @Flags
  enum class Permission(private val wireValue: Int) : ArcEnum {
      None(0),
      Read(1),
      Write(2);

      override fun value(): Int = wireValue
  }

  data class Grant(val permissions: Set<Permission>)
  ```

  A `Grant` holding `Read` and `Write` writes `{"permissions":[1,2]}` and reads back into the same set.
