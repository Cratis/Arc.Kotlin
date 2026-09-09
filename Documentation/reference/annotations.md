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

### Class and operation precedence

An operation — a command `handle` function or a read-model query function — that declares any `@Authorize` or `@Roles` replaces its class's declaration completely. Its policy, roles, and schemes are the only ones evaluated, and the class's are discarded. The class declaration applies only to operations that declare none. An operation can therefore only narrow access, never widen it: on a class requiring `admin`, a `@Roles("auditor")` operation admits auditors and rejects admins. Repeating `@Roles` on the same target still combines those roles, and a caller satisfies a role list by holding any one of its roles.

`@AllowAnonymous` cannot be combined with `@Authorize` or `@Roles`, on the same target or across a class and its operation. KSP reports `ARCKSP0108` and stops generation rather than resolving the combination. This is stricter than Arc .NET, which lets a method-level attribute override the class in that case; declare the artifact so that one level owns the decision.

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

Source-visible derived leaves generated in later KSP rounds are included in the reachable metadata graph even when they are not commands or read models themselves. Arc refreshes existing interface-property derivative associations with that graph, allowing generated proxies to include the leaf model and its discriminator mapping. An unrelated annotated hierarchy is not included merely because it has `@DerivedType` annotations.

### Runtime derived-type dispatch

Populate the `DerivedTypeRegistry` before reading registered base types with `ArcObjectMapper`. On Arc's ordinary discriminator path, runtime dispatch resolves `_derivedTypeId` only within the declared base type's registrations; it does not infer self registrations, search other bases, or follow a transitive chain of identifiers. On that path, a non-null value must be an object with a textual, known identifier whose registered target is assignable to that base. Explicit JSON `null` remains `null`.

On this ordinary Arc path, manual runtime registrations support multilevel inheritance. For an abstract `Root`, an annotated concrete `Middle` extending `Root`, and an annotated `Leaf` extending `Middle`, register each intended pair explicitly: `Root` → `Middle`, `Root` → `Leaf`, and `Middle` → `Leaf`. Reading `Root` with the middle identifier creates exactly `Middle`, even though `Middle` is also a registered base. Reading either `Root` or `Middle` with the leaf identifier creates `Leaf`. Reading `Middle` with its own identifier still fails unless `Middle` → `Middle` was explicitly registered.

Arc consumes only the current object's discriminator before binding the selected target. Nested properties and collection elements independently validate their identifiers against their own declared bases. Compatible generic type bindings and property-specific Jackson bean configuration are retained during target binding. On this ordinary path, when a registered target declares no type parameters of its own and the requested base is parameterized, Arc also checks the target's fixed inherited bindings before looking up or invoking its deserializer. A definite contradiction, such as a target extending `Base<String>` requested as `Base<Payload>`, fails during reading with `JsonMappingException` naming the target, requested base, and conflicting binding path rather than returning a value that fails at typed payload access. The check recursively compares available generic arguments, collection elements, map keys and values, reference contents, and array components, projecting compatible subtypes onto the requested raw type before comparing their arguments.

This is read compatibility, not mutable generic invariance: a fixed `String` binding can be read as `String`, `Object`, or `CharSequence`, and a fixed `List<String>` as `Collection<Object>`. Raw or unconstrained requests are allowed; absent or unresolved actual metadata, including Jackson's indistinguishable `Object` bindings and erased wildcard constraints, cannot establish a definite contradiction and are not rejected by this check. It does not inspect payload values, validate custom deserializer output or application-defined type policies, or replace Jackson's existing specialization checks for targets with their own type parameters. JSON `null` bases remain `null`; a null payload does not excuse a definite binding contradiction. These are runtime mapper capabilities, not changes to KSP model authoring: the `ARCKSP0305` concrete-property restriction above and existing generic model restrictions remain in force.

The Arc wire format is unchanged: annotated objects carry one `_derivedTypeId` alongside their ordinary properties. Updating readers on the ordinary Arc path retain replacement-read semantics rather than mutating an existing value, and strict Jackson merge configuration rejects those updates.

Existing native Jackson type dispatch, configured through `@JsonTypeInfo` or default typing, remains Jackson-owned rather than constrained by the Arc registry. The Arc exact-identifier and assignability checks above apply to its ordinary discriminator path, not to native subtype selection. For example, a native name resolver can select a subtype absent from the Arc base's registrations without `_derivedTypeId`; adding `_derivedTypeId` can instead fail as an unknown property on that selected bean. Preserving this existing dispatch is not an Arc round-trip guarantee or a claim that all native combinations are supported, safe, or wire compatible. Separate hardening is tracked in issue #120.

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
