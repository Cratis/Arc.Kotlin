// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.persistence.recipes.jpa

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.springdata.jpa.JpaPersistenceUnit
import io.cratis.arc.springdata.jpa.JpaPersistenceUnitResolver
import io.cratis.arc.springdata.jpa.JpaReadModelForCommandResolver
import io.cratis.arc.springdata.jpa.commandContext
import jakarta.persistence.AttributeConverter
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Table
import jakarta.persistence.metamodel.Type
import java.io.Serializable
import java.sql.SQLException
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory

internal class JpaConceptStorageTests {
    @Test
    fun `embedded UUID concept exposes one metamodel identifier and resolves through certified Arc unit`() {
        store().use { store ->
            val id = UuidId(UUID_VALUE)
            store.transaction { manager ->
                manager.persist(UuidRow(id, "Stored"))
                manager.flush()
                manager.clear()
                assertEquals(id, manager.find(UuidRow::class.java, UuidId(id.value())).id)
            }
            assertIdentifier(store, UuidRow::class.java, UuidId::class.java)
            val unit = JpaPersistenceUnit(store.factory(), null, "one", "recipes")
            val provider = JpaReadModelForCommandResolver(Units(unit), true)
            val resolved = resolve(provider, UuidRow::class.java, UuidId(id.value()), "one") as UuidRow
            assertEquals("Stored", resolved.label)
            assertEquals(id, resolved.id)
            assertEquals(id.hashCode(), resolved.id.hashCode())
        }
    }

    @Test
    fun `explicit UUID String and Long attribute converters bind predicates and dirty replacements without resave`() {
        store().use { store ->
            val id = UuidId(UUID_VALUE)
            val original = UuidRow(id, "Original").apply {
                uuidField = UuidValue(UUID_VALUE)
                textField = TextValue("Initial")
                numberField = LongValue(Long.MIN_VALUE)
            }
            store.transaction { manager ->
                val repository = JpaRepositoryFactory(manager).getRepository(UuidRepository::class.java)
                repository.saveAndFlush(original)
                manager.clear()
                val reloaded = repository.findById(UuidId(UUID_VALUE)).orElseThrow()
                assertNotSame(original, reloaded)
                assertEquals(original.uuidField, reloaded.uuidField)
                assertEquals(original.textField, reloaded.textField)
                assertEquals(original.numberField, reloaded.numberField)
                assertEquals(id, repository.findByUuidField(UuidValue(UUID_VALUE)).single().id)
                assertEquals(id, repository.findByTextField(TextValue("Initial")).single().id)
                assertEquals(id, repository.findByNumberField(LongValue(Long.MIN_VALUE)).single().id)
            }
            assertRaw(store, "k_concept_uuid", UUID_VALUE, UUID_VALUE, "Initial", Long.MIN_VALUE)
            store.transaction { manager ->
                val managed = manager.find(UuidRow::class.java, UuidId(UUID_VALUE))
                managed.uuidField = UuidValue(OTHER_UUID)
                managed.textField = TextValue("Replacement")
                managed.numberField = LongValue(Long.MAX_VALUE)
                manager.flush() // Managed replacement: deliberately no repository.save/merge.
                manager.clear()
                val loaded = manager.find(UuidRow::class.java, UuidId(UUID_VALUE))
                assertNotSame(managed, loaded)
                assertEquals(UuidValue(OTHER_UUID), loaded.uuidField)
                assertEquals(TextValue("Replacement"), loaded.textField)
                assertEquals(LongValue(Long.MAX_VALUE), loaded.numberField)
            }
            assertRaw(store, "k_concept_uuid", UUID_VALUE, OTHER_UUID, "Replacement", Long.MAX_VALUE)
            store.read { manager ->
                val repository = JpaRepositoryFactory(manager).getRepository(UuidRepository::class.java)
                assertTrue(repository.findByTextField(TextValue("Initial")).isEmpty())
                assertEquals(id, repository.findByUuidField(UuidValue(OTHER_UUID)).single().id)
                assertEquals(id, repository.findByTextField(TextValue("Replacement")).single().id)
                assertEquals(id, repository.findByNumberField(LongValue(Long.MAX_VALUE)).single().id)
            }
        }
    }

    @Test
    fun `String embedded identifier stays VARCHAR and nullable attributes survive fresh reload and replacement`() {
        store().use { store ->
            val scalar = "0123456789abcdef01234567"
            val id = StringId(scalar)
            store.transaction { manager ->
                val repository = JpaRepositoryFactory(manager).getRepository(StringRepository::class.java)
                repository.saveAndFlush(StringRow(id, "Nullable"))
                manager.clear()
                val loaded = repository.findById(StringId(scalar)).orElseThrow()
                assertEquals(id, loaded.id)
                assertEquals(id.hashCode(), loaded.id.hashCode())
                assertNull(loaded.uuidField)
                assertNull(loaded.textField)
                assertNull(loaded.numberField)
                loaded.uuidField = UuidValue(UUID_VALUE)
                loaded.textField = TextValue("Non-null")
                loaded.numberField = LongValue(0)
            }
            assertIdentifier(store, StringRow::class.java, StringId::class.java)
            assertRaw(store, "k_concept_string", scalar, UUID_VALUE, "Non-null", 0L)
            store.transaction { manager ->
                val loaded = manager.find(StringRow::class.java, StringId(scalar))
                assertEquals(TextValue("Non-null"), loaded.textField)
                loaded.uuidField = null
                loaded.textField = null
                loaded.numberField = null
                manager.flush()
                manager.clear()
                val empty = manager.find(StringRow::class.java, StringId(scalar))
                assertNull(empty.uuidField)
                assertNull(empty.textField)
                assertNull(empty.numberField)
            }
            assertRaw(store, "k_concept_string", scalar, null, null, null)
            store.read { manager ->
                val fresh = JpaRepositoryFactory(manager).getRepository(StringRepository::class.java)
                    .findById(StringId(scalar)).orElseThrow()
                assertNull(fresh.uuidField)
                assertNull(fresh.textField)
                assertNull(fresh.numberField)
            }
            val provider = JpaReadModelForCommandResolver(Units(JpaPersistenceUnit(store.factory(), null, "one", "recipes")), true)
            assertEquals(id, (resolve(provider, StringRow::class.java, StringId(scalar), "one") as StringRow).id)
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [Long.MIN_VALUE, -9007199254740993L, 0L, 9007199254740993L, Long.MAX_VALUE])
    fun `Long IDs and converted attributes retain signed 64 bit values`(scalar: Long) {
        store().use { store ->
            val id = LongId(scalar)
            store.transaction { manager ->
                val repository = JpaRepositoryFactory(manager).getRepository(LongRepository::class.java)
                repository.saveAndFlush(LongRow(id, "Exact").apply { numberField = LongValue(scalar) })
                manager.clear()
                val loaded = repository.findById(LongId(scalar)).orElseThrow()
                assertEquals(id, loaded.id)
                assertEquals(id.hashCode(), loaded.id.hashCode())
                assertEquals(LongValue(scalar), loaded.numberField)
                assertEquals(id, repository.findByNumberField(LongValue(scalar)).single().id)
            }
            assertIdentifier(store, LongRow::class.java, LongId::class.java)
            assertRaw(store, "k_concept_long", scalar, null, null, scalar)
            val provider = JpaReadModelForCommandResolver(Units(JpaPersistenceUnit(store.factory(), null, "one", "recipes")), true)
            assertEquals(id, (resolve(provider, LongRow::class.java, LongId(scalar), "one") as LongRow).id)
        }
    }

    @Test
    fun `malformed stored concepts fail construction and invalid SQL scalars are rejected visibly`() {
        store().use { store ->
            store.transaction { it.persist(UuidRow(UuidId(UUID_VALUE), "Invalid storage")) }
            store.jdbc().update("update k_concept_uuid set text_field = ''")
            val textFailure = assertThrows(RuntimeException::class.java) {
                store.read { it.find(UuidRow::class.java, UuidId(UUID_VALUE)) }
            }
            assertTrue(causes(textFailure).any { it is IllegalArgumentException && it.message == "Text concept must not be blank." })
            store.jdbc().update("update k_concept_uuid set text_field = null, uuid_field = ?", UUID(0, 0))
            val uuidFailure = assertThrows(RuntimeException::class.java) {
                store.read { it.find(UuidRow::class.java, UuidId(UUID_VALUE)) }
            }
            assertTrue(causes(uuidFailure).any { it is IllegalArgumentException && it.message == "UUID concept must not be nil." })
            for (sql in listOf(
                "update k_concept_uuid set uuid_field = 'not-a-uuid'",
                "update k_concept_uuid set number_field = '9223372036854775808'"
            )) {
                val failure = assertThrows(DataAccessException::class.java) { store.jdbc().update(sql) }
                assertTrue(causes(failure).filterIsInstance<SQLException>().any { it.sqlState.startsWith("22") })
            }
        }
    }

    @Test
    fun `missing explicit field mapping fails factory creation even when a non auto applying converter is registered`() {
        // Application recipe omission control, not a reproduction of an existing Arc product defect.
        val failure = assertThrows(RuntimeException::class.java) {
            RecipeJpaStore(UnconvertedRow::class.java, TextConverter::class.java).use { it.factory() }
        }
        assertTrue(causes(failure).any {
            it.message.orEmpty().contains("Could not determine recommended JdbcType") &&
                it.message.orEmpty().contains(TextValue::class.java.name)
        })
    }

    @Test
    fun `same concept keys remain tenant isolated and bad keys certificates and namespaces never retry a fixed unit`() {
        store().use { first ->
            store().use { second ->
                val keys = listOf(UuidId(UUID_VALUE), StringId("same-key"), LongId(Long.MIN_VALUE))
                val types = listOf(UuidRow::class.java, StringRow::class.java, LongRow::class.java)
                for ((store, label) in listOf(first to "First", second to "Second")) {
                    store.transaction { manager ->
                        manager.persist(UuidRow(UuidId(UUID_VALUE), label).apply { textField = TextValue(label) })
                        manager.persist(StringRow(StringId("same-key"), label).apply { textField = TextValue(label) })
                        manager.persist(LongRow(LongId(Long.MIN_VALUE), label).apply { textField = TextValue(label) })
                    }
                }
                val firstUnit = JpaPersistenceUnit(first.factory(), null, "one", "recipes")
                val units = Units(firstUnit, JpaPersistenceUnit(second.factory(), null, "two", "recipes"))
                val provider = JpaReadModelForCommandResolver(units, true)
                types.zip(keys).forEach { (type, key) ->
                    assertEquals(TextValue("First"), (resolve(provider, type, key, "one") as Fields).textField)
                    assertEquals(TextValue("Second"), (resolve(provider, type, key, "two") as Fields).textField)
                    units.calls.clear()
                    val wrongKey = assertThrows(IllegalArgumentException::class.java) {
                        resolve(provider, type, key.value(), "one")
                    }
                    assertTrue(wrongKey.message.orEmpty().contains("must be an instance of"))
                    assertEquals(listOf("one" to "recipes"), units.calls)
                    for ((tenant, namespace) in listOf("unknown" to "recipes", "one" to "wrong")) {
                        units.calls.clear()
                        val failure = assertThrows(IllegalStateException::class.java) {
                            resolve(provider, type, key, tenant, namespace)
                        }
                        assertTrue(failure.message.orEmpty().contains("No JPA persistence unit"))
                        assertEquals(listOf(tenant to namespace), units.calls)
                    }
                    units.calls.clear()
                    assertThrows(IllegalArgumentException::class.java) { resolve(provider, type, key, null) }
                    assertTrue(units.calls.isEmpty())
                    val mismatchCalls = mutableListOf<Pair<String?, String?>>()
                    val mismatch = JpaReadModelForCommandResolver(object : JpaPersistenceUnitResolver {
                        override fun readModelTypes(): Set<Class<*>> = firstUnit.readModelTypes()
                        override fun resolve(tenantId: String?, tenantNamespace: String?): JpaPersistenceUnit {
                            mismatchCalls += tenantId to tenantNamespace
                            return firstUnit
                        }
                    }, true)
                    val failure = assertThrows(IllegalStateException::class.java) { resolve(mismatch, type, key, "two") }
                    assertTrue(failure.message.orEmpty().contains("certificate does not match"))
                    assertEquals(listOf("two" to "recipes"), mismatchCalls)
                }
                units.calls.clear()
                assertThrows(IllegalArgumentException::class.java) {
                    resolve(provider, UuidRow::class.java, UuidValue(UUID_VALUE), "one")
                }
                assertEquals(listOf("one" to "recipes"), units.calls)
                assertNull(resolve(provider, UuidRow::class.java, UuidId(OTHER_UUID), "one"))
            }
        }
    }

    private fun store(): RecipeJpaStore = RecipeJpaStore(
        UuidRow::class.java, StringRow::class.java, LongRow::class.java,
        UuidId::class.java, StringId::class.java, LongId::class.java, Fields::class.java,
        UuidConverter::class.java, TextConverter::class.java, LongConverter::class.java
    )

    private fun assertIdentifier(store: RecipeJpaStore, entity: Class<*>, identifier: Class<*>) {
        val model = store.factory().metamodel.entity(entity)
        assertTrue(model.hasSingleIdAttribute())
        assertEquals(identifier, model.idType.javaType)
        assertEquals(Type.PersistenceType.EMBEDDABLE, model.idType.persistenceType)
        assertEquals(1, store.factory().metamodel.embeddable(identifier).attributes.size)
    }

    private fun assertRaw(store: RecipeJpaStore, table: String, id: Any, uuid: UUID?, text: String?, number: Long?) {
        store.jdbc().query("select concept_id, uuid_field, text_field, number_field from $table") { result ->
            assertEquals(id, result.getObject(1))
            assertEquals(uuid, result.getObject(2))
            assertEquals(text, result.getObject(3))
            assertEquals(number, result.getObject(4))
            assertEquals(when (id) { is UUID -> "UUID"; is Long -> "BIGINT"; else -> "CHARACTER VARYING" }, result.metaData.getColumnTypeName(1))
            assertEquals("UUID", result.metaData.getColumnTypeName(2))
            assertEquals("CHARACTER VARYING", result.metaData.getColumnTypeName(3))
            assertEquals("BIGINT", result.metaData.getColumnTypeName(4))
        }
        assertEquals(1L, store.jdbc().queryForObject("select count(*) from $table", Long::class.java))
        assertEquals(5L, store.jdbc().queryForObject(
            "select count(*) from information_schema.columns where table_name = ?",
            Long::class.java, table.uppercase(java.util.Locale.ROOT)
        ))
    }

    private fun resolve(provider: JpaReadModelForCommandResolver, type: Class<*>, key: Any, tenant: String?, namespace: String = "recipes"): Any? =
        provider.resolveBlocking(type, commandContext(tenant, namespace, key), key)

    private fun causes(failure: Throwable): List<Throwable> = generateSequence(failure) { it.cause }.toList()

    private class Units(vararg units: JpaPersistenceUnit) : JpaPersistenceUnitResolver {
        private val byTenant = units.associateBy { it.tenantId to it.tenantNamespace }
        private val fixed = JpaPersistenceUnit.fixed(units.first().entityManagerFactory)
        val calls = mutableListOf<Pair<String?, String?>>()
        override fun readModelTypes(): Set<Class<*>> = byTenant.values.flatMap { it.readModelTypes() }.toSet()
        override fun resolve(tenantId: String?, tenantNamespace: String?): JpaPersistenceUnit? {
            calls += tenantId to tenantNamespace
            return if (tenantId == null && tenantNamespace == null) fixed else byTenant[tenantId to tenantNamespace]
        }
    }

    // Mutable scalar slots are for JPA field hydration only. Never mutate a managed entity's identifier.
    @Embeddable
    data class UuidId(var scalar: UUID = UUID(0, 0)) : ConceptAs<UUID>, Serializable {
        override fun value(): UUID = scalar
    }

    @Embeddable
    data class StringId(var scalar: String = "") : ConceptAs<String>, Serializable {
        override fun value(): String = scalar
    }

    @Embeddable
    data class LongId(var scalar: Long = 0) : ConceptAs<Long>, Serializable {
        override fun value(): Long = scalar
    }

    data class UuidValue(private val scalar: UUID) : ConceptAs<UUID> {
        init { require(scalar != UUID(0, 0)) { "UUID concept must not be nil." } }
        override fun value(): UUID = scalar
    }

    data class TextValue(private val scalar: String) : ConceptAs<String> {
        init { require(scalar.isNotBlank()) { "Text concept must not be blank." } }
        override fun value(): String = scalar
    }

    data class LongValue(private val scalar: Long) : ConceptAs<Long> {
        override fun value(): Long = scalar
    }

    @Converter(autoApply = false)
    class UuidConverter : AttributeConverter<UuidValue, UUID> {
        override fun convertToDatabaseColumn(attribute: UuidValue?): UUID? = attribute?.value()
        override fun convertToEntityAttribute(dbData: UUID?): UuidValue? = dbData?.let(::UuidValue)
    }

    @Converter(autoApply = false)
    class TextConverter : AttributeConverter<TextValue, String> {
        override fun convertToDatabaseColumn(attribute: TextValue?): String? = attribute?.value()
        override fun convertToEntityAttribute(dbData: String?): TextValue? = dbData?.let(::TextValue)
    }

    @Converter(autoApply = false)
    class LongConverter : AttributeConverter<LongValue, Long> {
        override fun convertToDatabaseColumn(attribute: LongValue?): Long? = attribute?.value()
        override fun convertToEntityAttribute(dbData: Long?): LongValue? = dbData?.let(::LongValue)
    }

    @MappedSuperclass
    open class Fields {
        @field:Convert(converter = UuidConverter::class)
        @field:Column(name = "uuid_field")
        open var uuidField: UuidValue? = null
        @field:Convert(converter = TextConverter::class)
        @field:Column(name = "text_field")
        open var textField: TextValue? = null
        @field:Convert(converter = LongConverter::class)
        @field:Column(name = "number_field")
        open var numberField: LongValue? = null
    }

    @Entity(name = "KotlinConceptUuidRow")
    @Table(name = "k_concept_uuid")
    @ReadModel
    open class UuidRow(
        @field:EmbeddedId
        @field:AttributeOverride(name = "scalar", column = Column(name = "concept_id"))
        open var id: UuidId = UuidId(),
        open var label: String = ""
    ) : Fields()

    @Entity(name = "KotlinConceptStringRow")
    @Table(name = "k_concept_string")
    @ReadModel
    open class StringRow(
        @field:EmbeddedId
        @field:AttributeOverride(name = "scalar", column = Column(name = "concept_id"))
        open var id: StringId = StringId(),
        open var label: String = ""
    ) : Fields()

    @Entity(name = "KotlinConceptLongRow")
    @Table(name = "k_concept_long")
    @ReadModel
    open class LongRow(
        @field:EmbeddedId
        @field:AttributeOverride(name = "scalar", column = Column(name = "concept_id"))
        open var id: LongId = LongId(),
        open var label: String = ""
    ) : Fields()

    @Entity(name = "KotlinUnconvertedConceptRow")
    open class UnconvertedRow(
        @field:Id open var id: String = "",
        open var textField: TextValue? = null
    )

    interface UuidRepository : JpaRepository<UuidRow, UuidId> {
        fun findByUuidField(value: UuidValue): List<UuidRow>
        fun findByTextField(value: TextValue): List<UuidRow>
        fun findByNumberField(value: LongValue): List<UuidRow>
    }

    interface StringRepository : JpaRepository<StringRow, StringId>

    interface LongRepository : JpaRepository<LongRow, LongId> {
        fun findByNumberField(value: LongValue): List<LongRow>
    }

    private companion object {
        val UUID_VALUE: UUID = UUID.fromString("e980bb77-0727-4b8a-986d-576a826cb430")
        val OTHER_UUID: UUID = UUID.fromString("c17b2970-3c90-4936-8a94-ff38e2328563")
    }
}
