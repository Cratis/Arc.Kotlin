---
title: Use Spring Data read models
description: Inject JPA and MongoDB repositories into model-bound queries, translate Arc paging, and use command transactions.
---

## Add an integration

Choose the store used by the application:

```kotlin
// JPA
dependencies {
    implementation("io.cratis:arc-spring-data-jpa:<version>")
}
```

```kotlin
// MongoDB
dependencies {
    implementation("io.cratis:arc-spring-data-mongodb:<version>")
}
```

Both modules include the Arc Spring Boot starter and the corresponding Spring Data starter. Spring Boot continues to own the datasource, entity manager, Mongo client, repository discovery, and their standard configuration.

## Inject a repository into a query

Spring Data repositories are ordinary Spring services. Mark a model-bound query dependency with `@FromServices`; Arc's generated performer resolves the repository from the current application context.

```kotlin
@Entity
@ReadModel
data class TaskView(@Id val id: String = "", val title: String = "") {
    companion object {
        @JvmStatic
        fun all(@FromServices tasks: TaskViewRepository): List<TaskView> = tasks.findAll()
    }
}

interface TaskViewRepository : JpaRepository<TaskView, String>
```

The same pattern works with `MongoRepository`, repository fragments, and application query services. An application bean replaces an auto-configured adapter bean of the same integration contract.

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

## Map MongoDB concepts explicitly in the application

MongoDB concept storage is also an **application-owned recipe**, not Arc autodiscovery. Register
concrete, stateless `@WritingConverter`/`@ReadingConverter` pairs for each concept and scalar in
one authoritative `MongoCustomConversions.create` configuration. A bare Spring `Converter` bean
is not this registration. Configure the application's mapping context and converter before
creating templates, repositories, or tenant certificates; Arc must not mutate an application-owned
custom `MongoConverter` during lookup.

### Register concrete scalar pairs

For the `TextValue` concept above, the Kotlin pair is:

```kotlin
@WritingConverter
class TextWrite : Converter<TextValue, String> {
    override fun convert(source: TextValue): String = source.value()
}

@ReadingConverter
class TextRead : Converter<String, TextValue> {
    override fun convert(source: String): TextValue = TextValue(source)
}
```

The ordinary Java equivalent constructs the record explicitly:

```java
@WritingConverter
public class TextWrite implements Converter<TextValue, String> {
    @Override
    public String convert(TextValue source) { return source.value(); }
}

@ReadingConverter
public class TextRead implements Converter<String, TextValue> {
    @Override
    public TextValue convert(String source) { return new TextValue(source); }
}
```

Here `Converter` means Spring's `org.springframework.core.convert.converter.Converter`, not the
JPA annotation. The executable recipes provide equivalent concrete UUID-to-UUID and Long-to-Long
pairs named `UuidWrite`/`UuidRead` and `LongWrite`/`LongRead`. Do not replace them with an erased
`Converter<ConceptAs<?>, ?>` or infer constructors reflectively. Install all six together:

```kotlin
val conversions = MongoCustomConversions.create { adapter ->
    adapter.registerConverter(UuidWrite())
    adapter.registerConverter(UuidRead())
    adapter.registerConverter(TextWrite())
    adapter.registerConverter(TextRead())
    adapter.registerConverter(LongWrite())
    adapter.registerConverter(LongRead())
}
```

```java
var conversions = MongoCustomConversions.create(adapter -> {
    adapter.registerConverter(new UuidWrite());
    adapter.registerConverter(new UuidRead());
    adapter.registerConverter(new TextWrite());
    adapter.registerConverter(new TextRead());
    adapter.registerConverter(new LongWrite());
    adapter.registerConverter(new LongRead());
});
```

For programmatically owned templates, use that same configuration in both places, in this order
(the complete `RecipeMongoStore` fixture also owns and closes its client):

```java
var mappingContext = new MongoMappingContext();
mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
mappingContext.setInitialEntitySet(Set.of(UuidRow.class, TextRow.class, LongRow.class));
mappingContext.afterPropertiesSet();

var factory = new SimpleMongoClientDatabaseFactory(client, database);
var converter = new MappingMongoConverter(new DefaultDbRefResolver(factory), mappingContext);
converter.setCustomConversions(conversions);
converter.afterPropertiesSet();
var template = new MongoTemplate(factory, converter);
var repository = new MongoRepositoryFactory(template).getRepository(TextRows.class);
```

Configure the client explicitly with
`MongoClientSettings.builder().uuidRepresentation(UuidRepresentation.STANDARD)` before building
it. The tests read raw `BsonDocument` values and assert UUID binary **subtype 4**, BSON string,
and BSON int64 for IDs and ordinary fields, not nested wrappers or numeric strings.

### Choose identifier and null mappings deliberately

The recipes use Spring Data `@Id` for UUID and Long concept identifiers, with the concrete pairs
above. For the String concept identifier, use `@MongoId(FieldType.STRING)` explicitly: the tested
24-hex value `507f1f77bcf86cd799439011` stays a BSON string instead of becoming an `ObjectId`.
Do not substitute an unqualified `@MongoId` for these mappings; its implicit target is not the
same conversion policy. Declare repository IDs as the concrete concept type, for example
`MongoRepository<TextRow, TextValue>`.

Kotlin documents use constructor-bound concept IDs and mutable ordinary fields. Ordinary Java
documents use no-argument construction and field hydration, with immutable concept records as
their values; this avoids relying on Java constructor parameter-name compiler metadata. Do not
change an identifier after saving a document.

Spring bypasses these scalar converters for null. Apply `@Field(write = Field.Write.ALWAYS)`
(`@field:Field(write = Field.Write.ALWAYS)` in Kotlin) when a nullable concept field must be
written as explicit BSON null. Without it, the tested null field is absent. Both read as null;
the tests separately remove an explicitly written field and verify absent-field hydration.
Non-null blank text fails in the concept constructor. A stored String in the Long field fails
with `ConverterNotFoundException` rather than becoming a default Long concept; the configured
reading pair accepts Long, not arbitrary malformed storage types.

MongoDB documents are not JPA managed entities: changing a loaded field alone does not persist
it. Call repository `save` explicitly or use a mapped `MongoTemplate.updateFirst` with a
concept-valued query and update. Verify with another read through a fresh template/converter.

### MongoDB recipe evidence and limits

`Integrations/SpringDataMongo` tests `io.cratis.arc.persistence.recipes.mongodb.MongoConceptStorageTests`
and `JavaMongoConceptStorageTests` execute configured repositories, concept `findById` and equality
predicates, fresh class reconstruction, explicit save/update, distinct null/absent BSON shapes,
malformed-value failures, and exact signed Long boundaries including values beyond JavaScript's
exact integer range. The missing-registration control stores a nested concept document instead
of a scalar; adding the application registration produces the asserted scalar. This is an
application omission control, not an existing Arc product bug.

Each language stores the same UUID, String, and Long concept keys with different values in two
databases. Templates are fully configured before issuing `TenantMongoOperations` certificates.
The existing contextual resolver rejects unknown tenants, mismatched certificates, missing/blank
tenants, scalar keys, and a different concept key class. Exact resolver-call and driver `find`
command counts prove no retry or fallback database read. Mongo certificates certify `tenantId`,
not JPA's tenant namespace; no Mongo namespace-isolation claim follows. Repository injection
alone remains ordinary Spring dependency injection, not tenant routing.

This evidence uses Spring Data MongoDB 5.1.1 and Mongo Java driver 5.8.1 against the existing
**mongo-java-server 1.47.0 `MemoryBackend` emulator**, not a real MongoDB server, Testcontainers,
or an external database. It does not certify production MongoDB, other providers or BSON types,
change streams, replica sets, transactions, optimistic locking, or Arc .NET parity. No provider
dependency or Arc API is added by the recipes.

```shell
./gradlew :Integrations:SpringDataMongo:test --tests '*MongoConceptStorageTests'
```

## Consume paging and sorting

Arc carries zero-based paging and one sort field in `QueryRequest`. A model-bound query can declare the exact non-null Spring Data Commons `Pageable` and `Sort` types. Generated performers create both values directly from the captured query request; they do not use request scope, thread-local state, or a request-scoped bean.

```kotlin
@JvmStatic
fun all(
    pageable: Pageable,
    sort: Sort,
    @FromServices tasks: TaskViewRepository
): Page<TaskView> {
    check(pageable.sort == sort)
    return tasks.findAll(pageable)
}
```

A page size of zero becomes `Pageable.unpaged(sort)`. A blank sort field becomes `Sort.unsorted()`. Exact Spring Data `Page<TaskView>` returns are normalized by the generated performer before Arc renderers run, preserving `content`, zero-based page number, and the repository's pre-page `totalElements`; an unpaged result reports Arc page size zero, matching the compatibility adapters.

`Pageable` marks paging and sorting capabilities; `Sort` marks sorting capability. A query using either host adapter must return exact `Page<T>` so Arc cannot page or sort an already provider-shaped collection again. Client parameters cannot use the reserved names `page`, `pageSize`, `sortBy`, or `sortDirection`. These explicit generated descriptor flags control TypeScript and OpenAPI paging and sorting surfaces. Host-adapter parameters remain in declaration-order metadata for invocation and validation indexes, but Spring binding, Jakarta model-graph validation, TypeScript, OpenAPI, and endpoint introspection expose only client parameters.

The existing `JpaQueryRequestAdapter`, `JpaQueryPageAdapter`, `MongoQueryRequestAdapter`, and `MongoQueryPageAdapter` APIs remain available for manually implemented performers and compatibility. They use the same Spring Data Commons semantics as generated performers.

## Resolve current state for a command

The JPA and MongoDB integrations contribute storage-neutral `CanResolveReadModelForCommand` providers. A generated handler can therefore request its current read model directly; Arc uses the command's generated key and captured tenant context before invoking the handler:

```kotlin
@Command
data class RenameTask(@CommandKey val id: String, val title: String) {
    fun handle(current: TaskView) {
        // current came from the owning persistence provider using id.
    }
}
```

Only exact mapped types carrying `@ReadModel` are claimed. JPA uses `DECLARED` ownership because an entity mapping explicitly selects that store. MongoDB uses `FALLBACK`, so a declaring JPA, Chronicle, or application provider wins. Equal-strength claims fail startup rather than selecting a store by bean order. The supplied command key must already be an instance of the persistence identifier type; contextual providers do not recompute or coerce it. Registry-owned read-model parameters are resolved before ordinary Spring services, so a bean of the model class cannot bypass tenant or key selection. For an owned type, a missing row supplies `null` to Kotlin `T?` and `Optional.empty()` to an ordinary Java `Optional<T>`. A missing command key or missing required owned row produces one `dependencyUnavailable` validation result and HTTP 400. Resolver/storage failures and missing ordinary Spring services remain exceptions. Replacing Arc's `ReadModelForCommandResolverRegistry` bean is an expert override: the application then owns equivalent arbitration and tenancy guarantees.

The historical `JpaCommandReadModelResolver` and `MongoCommandReadModelResolver` APIs remain for fixed-store compatibility. Auto-configuration publishes them only with a verified fixed store and optional tenancy. They recompute a key without `CommandContext` and must not be used for tenant routing.

### Route JPA by tenant

Optional, single-unit applications receive a fixed `JpaPersistenceUnitResolver`. Tenant-routed applications provide one resolver that reports its exact type union and returns a `JpaPersistenceUnit` certified for the requested `tenantId` and `tenantNamespace`. The unit pairs its `EntityManagerFactory` with an optional matching `JpaTransactionManager`; Arc verifies both the certificate and mapped type before lookup. With `cratis.arc.tenancy.required=true`, mapped and resolver-owned JPA read-model claims must match, and the contextual provider must enforce the exact application resolver.

### Route MongoDB by tenant

A fixed `MongoOperationsResolver` serves only commands and observations without a tenant; supplying a tenant fails rather than reading and relabeling the default database. Tenant-routed applications provide one `TenantAwareMongoOperationsResolver`; each lookup returns `TenantMongoOperations`, which certifies the exact tenant identifier together with its isolated `MongoOperations`. Unknown tenants must throw, and Arc rejects mismatched certificates without retrying a default database. Required tenancy validates that command lookup, snapshots, and change streams use adapters for that exact resolver, with no unrelated plain resolver.

## Enroll commands in transactions

Imperative Spring transactions are disabled by default because JPA and MongoDB transaction managers bind resources to a thread while Arc command handlers may suspend and resume on another worker. Fixed-store applications can explicitly opt in:

```yaml
cratis:
  arc:
    spring-data:
      jpa:
        command-transactions-enabled: true
      mongodb:
        command-transactions-enabled: true
```

Opt-in succeeds only for a verified fixed store with an identity-aligned transaction manager. Dynamic tenant resolvers never receive a fixed transaction scope. Even when enabled, command code must not suspend or execute persistence work on another thread; the scope is imperative, not coroutine-safe. The scopes commit only a successful final `CommandResult` and roll back failures when completion remains on the opening thread.

There is no distributed transaction across JPA, MongoDB, and Chronicle. Chronicle now completes after the local scopes, so a JPA or MongoDB completion failure prevents the append. Partial outcomes remain possible in the other direction: MongoDB may commit before JPA fails, and both local stores may commit before Chronicle fails or has an indeterminate external outcome. Imperative coroutine affinity and cross-store reconciliation remain explicit limitations.

## Return observable storage snapshots

Both Spring Data integrations publish injectable query services whose methods return Kotlin `Flow`. A Flow is cold by default: it reads the initial snapshot when collected, owns its subscription, and closes it on cancellation. `observeShared` is the explicit shared alternative and replays one complete snapshot while subscribers exist. Java callers can use the demand-aware `observePublisher` methods or callback overloads, whose returned `AutoCloseable` cancels collection.

### Observe MongoDB

Inject `MongoObservableQuery` into a model-bound query. `observe`, `observeSingle`, and `observeById` re-run the Spring Data query after insert, update, replace, delete, or invalidate notifications:

```kotlin
@JvmStatic
fun observeTasks(
    @FromServices queries: MongoObservableQuery
): Flow<List<TaskView>> = queries.observe(TaskView::class.java)

@JvmStatic
fun observeTask(
    id: String,
    @FromServices queries: MongoObservableQuery
): Flow<TaskView> = queries.observeById(TaskView::class.java, id)
```

`MongoChangeStreamWatcher` is the change-stream SPI. The default `ReconnectingMongoChangeStreamWatcher` uses `SpringDataMongoChangeStreamSource`, resumes from the last token after transient failures, applies capped exponential backoff, and closes its cursor when the collector is canceled. Cursor operations run on `Dispatchers.IO` without a hidden `flowOn` channel. `MongoObservationOptions.bufferCapacity` is exact: zero keeps the producer and collector in rendezvous, while a positive value permits only that many queued changes. MongoDB change streams require a replica set or sharded cluster.

A custom `MongoOperationsResolver` can select operations from the captured `tenantId`; it must not consult thread-local request state. Pass the tenant explicitly to the observation method. The default resolver uses the application's single `MongoOperations` bean. A `Query` supplies filtering, and `observeById` additionally narrows change notifications by document key. Updates and replacements always trigger a fresh snapshot, so a document that stops matching a filter is removed correctly.

### Observe JPA

Inject `JpaObservableQuery` and return `observe`, `observeList`, `observeSingle`, or `observeById` from the read-model query. The default list query uses the mapped JPA entity name; `JpaSnapshotQuery` provides a Java-friendly customization seam for predicates, ordering, and fetch joins.

```kotlin
@JvmStatic
fun observeTasks(
    @FromServices queries: JpaObservableQuery
): Flow<List<TaskView>> = queries.observe(TaskView::class.java)
```

JPA has no portable database change stream. `TransactionAwareDatabaseChangeNotifier` is therefore an explicit in-process publisher: call its `DatabaseChangePublisher.publish(TaskView::class.java, tenantId)` contract from the write side. Notifications are coalesced per transaction, discarded on rollback, and emitted only after commit. `JpaObservationOptions.bufferCapacity` must be positive and bounds pending invalidations; when it is full, the oldest invalidation is replaced because every notification causes a complete snapshot read. Snapshot delivery itself uses a rendezvous handoff rather than `flowOn`'s implicit buffer. The Flow performs its initial snapshot immediately, then debounce-coalesces committed changes into bounded replacement snapshots.

Applications needing cross-process notifications should replace the `DatabaseChangeNotifier` bean with a database-native implementation and expose a corresponding `DatabaseChangePublisher` where local writes also need publishing. Arc does not silently poll either store; polling must be an application-owned, explicitly configured notifier.

## Current public-seam boundary

The integrations deliberately use public Arc and Spring contracts. Contextual command read-model parameters, deterministic ownership, generated `QueryRequest`/`QueryContext` injection, exact Spring Data Commons `Pageable`/`Sort` parameters, and exact `Page<T>` response normalization are implemented. The store-specific request and page adapters remain compatibility utilities rather than request-scoped beans.

Repository injection remains ordinary Spring dependency injection and is not automatically tenant-routed by Arc. JPA observable snapshots still use their configured entity-manager factory; tenant labels on notifications are not storage isolation. Mongo observable paths use `MongoOperationsResolver`, including the certified adapter when a tenant-aware resolver is supplied. Imperative transaction scopes remain explicit fixed-store opt-ins and are not coroutine-safe.
