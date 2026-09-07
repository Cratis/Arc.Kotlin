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
| `@DerivedType(id)` | Class | Adds `_derivedTypeId` and registers a stable identifier for polymorphic Arc JSON. The identifier must be nonblank and unique for its base type. Code generation records the base-to-derivative mappings, and a host registers them so the identifier resolves when reading. See [Polymorphic reads](#polymorphic-reads). |
| `@Flags` | Class | Marks an enum as a bit field. Generated TypeScript gains an `all<Name>` constant combining every nonzero member. Nothing about JVM serialization changes. |
| `@ArcEnumValue(value)` | Field | Declares an enum member's integer wire value where KSP cannot prove it from a single integer-literal constructor argument. |

Arc writes an enum as an integer: the result of `value()` when the enum implements `ArcEnum`, and the ordinal otherwise. Reading accepts that integer or the member name matched case-insensitively, and rejects anything else.

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

### Polymorphic reads

`@DerivedType` on its own only affects writing. Resolving `_derivedTypeId` back to a class is the job of `DerivedTypeRegistry`, and Arc never scans the classpath to fill it. Code generation records every base-to-derivative mapping it saw on the generated `ArcArtifactModule` as real class references — the interface a value is declared as, and each base class above it — and the Spring Boot starter registers all of them into its `DerivedTypeRegistry` bean before Jackson reads anything.

Outside Spring, the same metadata populates a registry directly:

```kotlin
val registry = ConcurrentDerivedTypeRegistry()
ArcArtifactModuleRegistry.registerDerivedTypes(module, registry)
val mapper = ArcObjectMapper.create(registry)
```

A hierarchy that arrives from a dependency binary Arc's code generation never processed has no generated mapping. Register one with a `DerivedTypeRegistrar` bean; registrars run after the generated registrations, in Spring `@Order`:

```kotlin
@Bean
fun externalShapes() = DerivedTypeRegistrar { registry ->
    registry.register(Shape::class.java, ExternalCircle::class.java)
}
```

An application that declares its own `DerivedTypeRegistry` bean replaces Arc's entirely, generated registrations included.

Reading fails closed. An identifier the registry cannot resolve, and a value whose base type has registrations but that carries no `_derivedTypeId`, are both refused with a mapping error that names the base type; over HTTP that becomes the ordinary safe `malformedRequest` envelope. Arc .NET refuses an unresolvable identifier the same way, but returns `null` when the discriminator is absent — an intentional divergence, because a silently absent property is harder to diagnose than a rejected request.
