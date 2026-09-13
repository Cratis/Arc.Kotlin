// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.persistence.recipes.mongodb

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.springdata.mongodb.MongoReadModelForCommandResolver
import io.cratis.arc.springdata.mongodb.TenantAwareMongoOperationsResolver
import io.cratis.arc.springdata.mongodb.TenantMongoOperations
import java.util.UUID
import org.bson.BsonDocument
import org.bson.BsonType
import org.bson.UuidRepresentation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.convert.ConverterNotFoundException
import org.springframework.core.convert.converter.Converter
import org.springframework.data.annotation.Id
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.mongodb.core.convert.MongoCustomConversions
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import org.springframework.data.mongodb.core.mapping.FieldType
import org.springframework.data.mongodb.core.mapping.MongoId
import org.springframework.data.mongodb.core.query.Criteria.where
import org.springframework.data.mongodb.core.query.Query.query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory

class MongoConceptStorageTests {
    data class UuidValue(private val scalar: UUID) : ConceptAs<UUID> {
        override fun value(): UUID = scalar
    }

    data class TextValue(private val scalar: String) : ConceptAs<String> {
        init { require(scalar.isNotBlank()) { "Text concept must not be blank." } }
        override fun value(): String = scalar
    }

    data class OtherTextValue(private val scalar: String) : ConceptAs<String> {
        override fun value(): String = scalar
    }

    data class LongValue(private val scalar: Long) : ConceptAs<Long> {
        override fun value(): Long = scalar
    }

    @WritingConverter
    class UuidWrite : Converter<UuidValue, UUID> {
        override fun convert(source: UuidValue): UUID = source.value()
    }

    @ReadingConverter
    class UuidRead : Converter<UUID, UuidValue> {
        override fun convert(source: UUID): UuidValue = UuidValue(source)
    }

    @WritingConverter
    class TextWrite : Converter<TextValue, String> {
        override fun convert(source: TextValue): String = source.value()
    }

    @ReadingConverter
    class TextRead : Converter<String, TextValue> {
        override fun convert(source: String): TextValue = TextValue(source)
    }

    @WritingConverter
    class LongWrite : Converter<LongValue, Long> {
        override fun convert(source: LongValue): Long = source.value()
    }

    @ReadingConverter
    class LongRead : Converter<Long, LongValue> {
        override fun convert(source: Long): LongValue = LongValue(source)
    }

    open class Fields {
        @field:Field(write = Field.Write.ALWAYS)
        var uuidField: UuidValue? = null
        @field:Field(write = Field.Write.ALWAYS)
        var textField: TextValue? = null
        @field:Field(write = Field.Write.ALWAYS)
        var longField: LongValue? = null
        var absentField: TextValue? = null
    }

    @ReadModel
    @Document("k_uuid")
    class UuidRow(@field:Id val id: UuidValue) : Fields()

    @ReadModel
    @Document("k_text")
    class TextRow(@field:MongoId(FieldType.STRING) val id: TextValue) : Fields()

    @ReadModel
    @Document("k_long")
    class LongRow(@field:Id val id: LongValue) : Fields()

    @Document("k_missing")
    class MissingRow(@field:MongoId val id: String, val textField: TextValue)

    interface UuidRows : MongoRepository<UuidRow, UuidValue> {
        fun findByTextField(value: TextValue): List<UuidRow>
    }

    interface TextRows : MongoRepository<TextRow, TextValue>
    interface LongRows : MongoRepository<LongRow, LongValue>

    private fun conversions(): MongoCustomConversions = MongoCustomConversions.create { adapter ->
        adapter.registerConverter(UuidWrite())
        adapter.registerConverter(UuidRead())
        adapter.registerConverter(TextWrite())
        adapter.registerConverter(TextRead())
        adapter.registerConverter(LongWrite())
        adapter.registerConverter(LongRead())
    }

    private fun store(): RecipeMongoStore = RecipeMongoStore(
        setOf(UuidRow::class.java, TextRow::class.java, LongRow::class.java), conversions()
    )

    private fun populated(row: Fields, text: String = "Stored"): Fields = row.apply {
        uuidField = UuidValue(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"))
        textField = TextValue(text)
        longField = LongValue(9007199254740993L)
    }

    private fun assertFields(expected: Fields, actual: Fields) {
        assertNotSame(expected, actual)
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.uuidField, actual.uuidField)
        assertEquals(expected.textField, actual.textField)
        assertEquals(expected.longField, actual.longField)
        assertEquals(UuidValue::class.java, actual.uuidField!!.javaClass)
        assertEquals(TextValue::class.java, actual.textField!!.javaClass)
        assertEquals(LongValue::class.java, actual.longField!!.javaClass)
    }

    private fun assertRawFields(raw: BsonDocument, row: Fields) {
        assertEquals(BsonType.BINARY, raw["uuidField"]!!.bsonType)
        assertEquals(4.toByte(), raw.getBinary("uuidField").type)
        assertEquals(row.uuidField!!.value(), raw.getBinary("uuidField").asUuid(UuidRepresentation.STANDARD))
        assertEquals(BsonType.STRING, raw["textField"]!!.bsonType)
        assertEquals(row.textField!!.value(), raw.getString("textField").value)
        assertEquals(BsonType.INT64, raw["longField"]!!.bsonType)
        assertEquals(row.longField!!.value(), raw.getInt64("longField").value)
    }

    @Test
    fun `UUID concept ID and fields round trip through repository and concept predicates`() {
        store().use { store ->
            val id = UuidValue(UUID.fromString("ffeeddcc-bbaa-9988-7766-554433221100"))
            val row = UuidRow(id).also { populated(it) }
            val template = store.template("primary")
            val repository = MongoRepositoryFactory(template).getRepository(UuidRows::class.java)
            repository.save(row)
            val raw = store.raw("primary", "k_uuid")
            assertEquals(4.toByte(), raw.getBinary("_id").type)
            assertEquals(id.value(), raw.getBinary("_id").asUuid(UuidRepresentation.STANDARD))
            assertRawFields(raw, row)
            assertFields(row, repository.findById(UuidValue(id.value())).orElseThrow())
            assertEquals(listOf(id), repository.findByTextField(TextValue("Stored")).map { it.id })
            assertEquals(id, template.findOne(query(where("uuidField").`is`(row.uuidField)), UuidRow::class.java)!!.id)
            assertEquals(id, template.findOne(query(where("longField").`is`(row.longField)), UuidRow::class.java)!!.id)
            val fresh = store.template("primary").findById(id, UuidRow::class.java)!!
            assertEquals(id, fresh.id)
            assertEquals(UuidValue::class.java, fresh.id.javaClass)
            assertFields(row, fresh)
        }
    }

    @Test
    fun `24 hex String concept ID stays BSON string and finds by concept key`() {
        store().use { store ->
            val id = TextValue("507f1f77bcf86cd799439011")
            val row = TextRow(id).also { populated(it) }
            val repository = MongoRepositoryFactory(store.template("primary")).getRepository(TextRows::class.java)
            repository.save(row)
            val raw = store.raw("primary", "k_text")
            assertEquals(BsonType.STRING, raw["_id"]!!.bsonType)
            assertEquals(id.value(), raw.getString("_id").value)
            assertRawFields(raw, row)
            assertEquals(id, repository.findById(TextValue(id.value())).orElseThrow().id)
            val fresh = store.template("primary").findById(id, TextRow::class.java)!!
            assertEquals(TextValue::class.java, fresh.id.javaClass)
            assertFields(row, fresh)
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [Long.MIN_VALUE, -9007199254740993L, 9007199254740993L, Long.MAX_VALUE])
    fun `Long concept IDs and fields preserve signed 64 bit values`(value: Long) {
        store().use { store ->
            val id = LongValue(value)
            val row = LongRow(id).also { populated(it); it.longField = id }
            val repository = MongoRepositoryFactory(store.template("primary")).getRepository(LongRows::class.java)
            repository.save(row)
            val raw = store.raw("primary", "k_long")
            assertEquals(BsonType.INT64, raw["_id"]!!.bsonType)
            assertEquals(value, raw.getInt64("_id").value)
            assertRawFields(raw, row)
            assertEquals(id, repository.findById(LongValue(value)).orElseThrow().id)
            val fresh = store.template("primary").findById(id, LongRow::class.java)!!
            assertEquals(LongValue::class.java, fresh.id.javaClass)
            assertFields(row, fresh)
            assertEquals(id, store.template("primary").findOne(query(where("longField").`is`(id)), LongRow::class.java)!!.id)
        }
    }

    @Test
    fun `explicit null fields and absent fields have distinct BSON shapes and read as null`() {
        store().use { store ->
            val row = TextRow(TextValue("nullable"))
            store.template("primary").save(row)
            val raw = store.raw("primary", "k_text")
            listOf("uuidField", "textField", "longField").forEach { assertEquals(BsonType.NULL, raw[it]!!.bsonType) }
            assertFalse(raw.containsKey("absentField"))
            val fresh = store.template("primary").findById(row.id, TextRow::class.java)!!
            assertNull(fresh.uuidField)
            assertNull(fresh.textField)
            assertNull(fresh.longField)
            assertNull(fresh.absentField)
            store.template("primary").getCollection("k_text").updateOne(
                org.bson.Document("_id", "nullable"), org.bson.Document("\$unset", org.bson.Document("textField", ""))
            )
            assertFalse(store.raw("primary", "k_text").containsKey("textField"))
            assertNull(store.template("primary").findById(row.id, TextRow::class.java)!!.textField)
        }
    }

    @Test
    fun `replacement requires explicit save and mapped update binds concept values`() {
        store().use { store ->
            val row = TextRow(TextValue("replace")).also { populated(it) }
            val template = store.template("primary")
            val repository = MongoRepositoryFactory(template).getRepository(TextRows::class.java)
            repository.save(row)
            val loaded = repository.findById(row.id).orElseThrow()
            loaded.textField = TextValue("Saved")
            assertEquals(TextValue("Stored"), store.template("primary").findById(row.id, TextRow::class.java)!!.textField)
            repository.save(loaded)
            assertEquals(TextValue("Saved"), store.template("primary").findById(row.id, TextRow::class.java)!!.textField)
            val update = template.updateFirst(query(where("id").`is`(row.id)), Update().set("longField", LongValue(Long.MIN_VALUE)), TextRow::class.java)
            assertEquals(1L, update.modifiedCount)
            assertEquals(Long.MIN_VALUE, store.raw("primary", "k_text").getInt64("longField").value)
            assertEquals(LongValue(Long.MIN_VALUE), store.template("primary").findById(row.id, TextRow::class.java)!!.longField)
        }
    }

    @Test
    fun `malformed stored scalar fails visibly without a default concept`() {
        store().use { store ->
            val row = TextRow(TextValue("invalid")).also { populated(it) }
            val template = store.template("primary")
            template.save(row)
            template.getCollection("k_text").updateOne(
                org.bson.Document("_id", "invalid"), org.bson.Document("\$set", org.bson.Document("textField", " "))
            )
            val failure = assertThrows(RuntimeException::class.java) { store.template("primary").findById(row.id, TextRow::class.java) }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message == "Text concept must not be blank." })
            template.getCollection("k_text").updateOne(
                org.bson.Document("_id", "invalid"), org.bson.Document("\$set", org.bson.Document("textField", "Valid").append("longField", "not-a-long"))
            )
            assertEquals("not-a-long", store.raw("primary", "k_text").getString("longField").value)
            // A stored String is outside the registered Long -> LongValue reading pair.
            val numericFailure = assertThrows(ConverterNotFoundException::class.java) {
                store.template("primary").findById(row.id, TextRow::class.java)
            }
            assertEquals(String::class.java, numericFailure.sourceType!!.type)
            assertEquals(LongValue::class.java, numericFailure.targetType.type)
        }
    }

    @Test
    fun `missing application mapping is an omission control not an Arc defect`() {
        RecipeMongoStore(setOf(MissingRow::class.java), MongoCustomConversions.create { }).use { store ->
            store.template("primary").save(MissingRow("missing", TextValue("Stored")))
            val raw = store.raw("primary", "k_missing")
            assertEquals(BsonType.DOCUMENT, raw["textField"]!!.bsonType)
            assertEquals("Stored", raw.getDocument("textField").getString("scalar").value)
        }
        RecipeMongoStore(setOf(MissingRow::class.java), conversions()).use { store ->
            store.template("primary").save(MissingRow("missing", TextValue("Stored")))
            val raw = store.raw("primary", "k_missing")
            assertEquals(BsonType.STRING, raw["textField"]!!.bsonType)
            assertEquals("Stored", raw.getString("textField").value)
        }
    }

    @Test
    fun `certified databases isolate same concept keys and reject invalid routing without retry`() {
        store().use { store ->
            val a = store.template("tenant_a")
            val b = store.template("tenant_b")
            val uuid = UuidValue(UUID.randomUUID())
            val text = TextValue("507f1f77bcf86cd799439011")
            val number = LongValue(Long.MAX_VALUE)
            listOf(a to "A", b to "B").forEach { (template, value) ->
                template.save(UuidRow(uuid).also { populated(it, value) })
                template.save(TextRow(text).also { populated(it, value) })
                template.save(LongRow(number).also { populated(it, value) })
            }
            store.clearFindDatabases()
            val calls = mutableListOf<String>()
            val resolver = TenantAwareMongoOperationsResolver { tenant ->
                calls.add(tenant)
                when (tenant) {
                    "a" -> TenantMongoOperations("a", a)
                    "b" -> TenantMongoOperations("b", b)
                    "mismatch" -> TenantMongoOperations("a", a)
                    else -> throw IllegalArgumentException("Unknown tenant $tenant")
                }
            }
            val provider = MongoReadModelForCommandResolver(store.mappingContext(), resolver, true)
            assertEquals(setOf(UuidRow::class.java, TextRow::class.java, LongRow::class.java), provider.readModelTypes())
            listOf(UuidRow::class.java to uuid, TextRow::class.java to text, LongRow::class.java to number).forEach { (type, key) ->
                assertEquals(TextValue("A"), (provider.resolveBlocking(type, context("a"), key) as Fields).textField)
                assertEquals(TextValue("B"), (provider.resolveBlocking(type, context("b"), key) as Fields).textField)
            }
            assertEquals(listOf("a", "b", "a", "b", "a", "b"), calls)
            assertThrows(IllegalArgumentException::class.java) { provider.resolveBlocking(TextRow::class.java, context("unknown"), text) }
            assertThrows(IllegalStateException::class.java) { provider.resolveBlocking(TextRow::class.java, context("mismatch"), text) }
            assertThrows(IllegalArgumentException::class.java) { provider.resolveBlocking(TextRow::class.java, context("a"), text.value()) }
            assertThrows(IllegalArgumentException::class.java) { provider.resolveBlocking(TextRow::class.java, context("a"), number) }
            assertThrows(IllegalArgumentException::class.java) { provider.resolveBlocking(TextRow::class.java, context("a"), OtherTextValue(text.value())) }
            assertThrows(IllegalArgumentException::class.java) { provider.resolveBlocking(TextRow::class.java, context(null), text) }
            assertThrows(IllegalArgumentException::class.java) { provider.resolveBlocking(TextRow::class.java, context(" "), text) }
            assertEquals(listOf("a", "b", "a", "b", "a", "b", "unknown", "mismatch"), calls)
            assertEquals(listOf("tenant_a", "tenant_b", "tenant_a", "tenant_b", "tenant_a", "tenant_b"), store.findDatabases())
        }
    }

    private fun context(tenant: String?): CommandContext = CommandContext(
        UUID.randomUUID(), Any(), Any::class.java, ArcPrincipal.anonymous(), tenant,
        serviceResolver = object : ServiceResolver {
            override fun <T : Any> resolve(type: Class<T>): T? = null
        }
    )
}
