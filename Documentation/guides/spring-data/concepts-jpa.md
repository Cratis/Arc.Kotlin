---
title: Map JPA concepts
description: Persist a ConceptAs value through a JPA attribute converter, including single-column embedded ids.
---

## Map JPA concepts explicitly in the application

`ConceptAs<T>` is not a persistence mapping or an automatic converter registration. Own each
concrete mapping in the application, configure it before creating and certifying each persistence
unit, and keep converters stateless and tenant-independent. Arc does not install converters during
lookup or infer concept constructors.

### Convert ordinary attributes

Use a concrete `AttributeConverter<SpecificConcept, Scalar>` with `@Converter(autoApply = false)`
and apply it with `@Convert` to each ordinary attribute. Preserve null in both directions; construct
non-null values explicitly and let invalid values fail rather than substituting a default:

```kotlin
data class TextValue(private val scalar: String) : ConceptAs<String> {
    init { require(scalar.isNotBlank()) { "Text concept must not be blank." } }
    override fun value(): String = scalar
}

@Converter(autoApply = false)
class TextConverter : AttributeConverter<TextValue, String> {
    override fun convertToDatabaseColumn(attribute: TextValue?): String? = attribute?.value()
    override fun convertToEntityAttribute(dbData: String?): TextValue? = dbData?.let(::TextValue)
}
```

The ordinary Java equivalent uses an explicit concrete record converter, not a generic erased
`ConceptAs` converter:

```java
public record TextValue(String value) implements ConceptAs<String> {
    public TextValue {
        Objects.requireNonNull(value);
        if (value.isBlank()) throw new IllegalArgumentException("Text concept must not be blank.");
    }
}

@Converter(autoApply = false)
public class TextConverter implements AttributeConverter<TextValue, String> {
    @Override
    public String convertToDatabaseColumn(TextValue attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public TextValue convertToEntityAttribute(String dbData) {
        return dbData == null ? null : new TextValue(dbData);
    }
}
```

The executable recipes also provide concrete UUID-to-UUID and Long-to-Long converters. Their H2
columns are `UUID`, `CHARACTER VARYING`, and `BIGINT`, not serialized wrapper objects. Numeric
coverage is signed 64-bit `Long` only, including both boundaries and values beyond JavaScript's
exact integer range; it does not establish another numeric type or a client numeric contract.

### Use a one-column embedded concept identifier

For concept identifiers, use `@Embeddable` and `@EmbeddedId` with one scalar attribute, an explicit
column override, and value equality. This is an **embedded identifier** recipe, not a claim that
an attribute converter on a basic `@Id` is portable. The Kotlin fixture supplies a JPA no-argument
constructor through defaults and mutable scalar slots for field hydration; never mutate an
identifier after assigning it to a managed entity:

```kotlin
@Embeddable
data class UuidId(var scalar: UUID = UUID(0, 0)) : ConceptAs<UUID>, Serializable {
    override fun value(): UUID = scalar
}

@Entity
@ReadModel
open class UuidRow(
    @field:EmbeddedId
    @field:AttributeOverride(name = "scalar", column = Column(name = "concept_id"))
    open var id: UuidId = UuidId()
) {
    @field:Convert(converter = TextConverter::class)
    @field:Column(name = "text_field")
    open var textField: TextValue? = null
}
```

The ordinary Java fixtures execute UUID, String, and Long **record embeddables** on Hibernate
7.4.5.Final with H2 2.5.250. Record support here is evidence for that provider/database combination,
not certification of other JPA providers:

```java
@Embeddable
public record UuidId(UUID value) implements ConceptAs<UUID>, Serializable { }

@Entity
@ReadModel
public class UuidRow {
    @EmbeddedId
    @AttributeOverride(name = "value", column = @Column(name = "concept_id"))
    public UuidId id;

    @Convert(converter = TextConverter.class)
    @Column(name = "text_field")
    public TextValue textField;

    public UuidRow() { }
}
```

Declare the repository identifier as the concept type, for example
`JpaRepository<UuidRow, UuidId>`, and supply `new UuidId(value)` from Java or `UuidId(value)` from
Kotlin. Concept-valued derived predicates bind through the ordinary attribute's converter. A
managed entity's ordinary concept attributes can be replaced and flushed without another `save`;
reload from a fresh persistence context to verify storage rather than the first-level cache.

### Evidence and routing boundary

`Integrations/SpringDataJpa` tests `io.cratis.arc.persistence.recipes.jpa.JpaConceptStorageTests`
and `JavaJpaConceptStorageTests` execute real in-process H2 storage with explicit per-unit mappings,
repository `findById`, concept-valued predicates, raw SQL column values and types, flush/clear and
fresh reload, nullable fields, dirty replacement, malformed stored values, and signed Long
boundaries. Missing `@Convert` fails factory creation even with a registered non-auto-applying
converter; that is an application mapping omission control, not an Arc product defect.

The tests first assert the provider's `hasSingleIdAttribute()` and embedded `idType`, then pass
that exact concept key through the existing contextual Arc resolver. The same keys with different
values in two certified H2 factories remain isolated; unknown tenants, wrong namespaces, mismatched
certificates, raw scalar keys, and a different concept class fail without a fixed-store retry.
Configure all mappings before issuing the `JpaPersistenceUnit` certificate. Repository injection
itself is still ordinary Spring dependency injection, not tenant routing. These application-owned
recipes add no Arc API, automatic persistence feature, or Arc .NET parity claim; other databases
and providers remain unverified.

Run the bounded recipe checks with:

```shell
./gradlew :Integrations:SpringDataJpa:test --tests '*JpaConceptStorageTests'
```
