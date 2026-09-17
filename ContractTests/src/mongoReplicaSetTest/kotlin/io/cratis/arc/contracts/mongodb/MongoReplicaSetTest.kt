// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.mongodb

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClients
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.springdata.mongodb.DefaultNamingPolicy
import io.cratis.arc.springdata.mongodb.MongoChange
import io.cratis.arc.springdata.mongodb.MongoChangeOperation
import io.cratis.arc.springdata.mongodb.MongoObservationOptions
import io.cratis.arc.springdata.mongodb.MongoOperationsResolver
import io.cratis.arc.springdata.mongodb.SpringDataMongoChangeStreamSource
import io.cratis.arc.springdata.mongodb.TenantAwareMongoOperationsResolver
import io.cratis.arc.springdata.mongodb.TenantContextMongoAccess
import io.cratis.arc.springdata.mongodb.TenantMongoOperations
import io.cratis.arc.tenancy.TenantContextBridge
import io.cratis.arc.tenancy.TenantId
import io.cratis.arc.tenancy.withTenant
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bson.BsonDocument
import org.bson.BsonType
import org.bson.UuidRepresentation
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.core.convert.converter.Converter
import org.springframework.data.annotation.Id
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver
import org.springframework.data.mongodb.core.convert.MappingMongoConverter
import org.springframework.data.mongodb.core.convert.MongoCustomConversions
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import org.springframework.data.mongodb.core.mapping.FieldType
import org.springframework.data.mongodb.core.mapping.MongoId
import org.springframework.data.mongodb.core.mapping.MongoMappingContext
import org.testcontainers.mongodb.MongoDBContainer
import org.testcontainers.utility.DockerImageName

/**
 * Real-server proof for MongoDB concept storage, tenant isolation, DefaultNamingPolicy routing, and
 * change-stream readiness.
 *
 * Each test proves a specific behavior that the `mongo-java-server` MemoryBackend emulator cannot
 * prove: real replica-set change streams, physical database isolation on a live MongoDB, correct BSON
 * wire types for concept IDs and fields, and null/malformed reading-converter behavior on a real
 * MongoDB 8.2 server.
 *
 * The gate never connects to a developer or production database. A digest-pinned
 * [MongoDBContainer] provides a disposable single-node replica set. Missing Docker fails the gate
 * rather than silently skipping it.
 */
class MongoReplicaSetTest {

    // ── Kotlin concept types ─────────────────────────────────────────────────

    data class KtUuidConcept(private val uuid: UUID) : ConceptAs<UUID> {
        override fun value(): UUID = uuid
    }

    data class KtStringConcept(private val str: String) : ConceptAs<String> {
        init { require(str.isNotBlank()) { "Must not be blank" } }
        override fun value(): String = str
    }

    data class KtLongConcept(private val num: Long) : ConceptAs<Long> {
        override fun value(): Long = num
    }

    // ── Converters ──────────────────────────────────────────────────────────

    @WritingConverter
    class KtUuidWrite : Converter<KtUuidConcept, UUID> {
        override fun convert(source: KtUuidConcept): UUID = source.value()
    }

    @ReadingConverter
    class KtUuidRead : Converter<UUID, KtUuidConcept> {
        override fun convert(source: UUID): KtUuidConcept = KtUuidConcept(source)
    }

    @WritingConverter
    class KtStringWrite : Converter<KtStringConcept, String> {
        override fun convert(source: KtStringConcept): String = source.value()
    }

    @ReadingConverter
    class KtStringRead : Converter<String, KtStringConcept> {
        override fun convert(source: String): KtStringConcept = KtStringConcept(source)
    }

    @WritingConverter
    class KtLongWrite : Converter<KtLongConcept, Long> {
        override fun convert(source: KtLongConcept): Long = source.value()
    }

    @ReadingConverter
    class KtLongRead : Converter<Long, KtLongConcept> {
        override fun convert(source: Long): KtLongConcept = KtLongConcept(source)
    }

    // ── Document entity types ────────────────────────────────────────────────

    @Document("rsr_kt_uuid")
    class KtUuidRow(@field:Id val id: KtUuidConcept) {
        @field:Field(write = Field.Write.ALWAYS) var uuidField: KtUuidConcept? = null
        @field:Field(write = Field.Write.ALWAYS) var textField: KtStringConcept? = null
        @field:Field(write = Field.Write.ALWAYS) var longField: KtLongConcept? = null
    }

    @Document("rsr_kt_string")
    class KtStringRow(@field:MongoId(FieldType.STRING) val id: KtStringConcept) {
        @field:Field(write = Field.Write.ALWAYS) var uuidField: KtUuidConcept? = null
        @field:Field(write = Field.Write.ALWAYS) var textField: KtStringConcept? = null
        @field:Field(write = Field.Write.ALWAYS) var longField: KtLongConcept? = null
        var absentField: KtStringConcept? = null
    }

    @Document("rsr_kt_long")
    class KtLongRow(@field:Id val id: KtLongConcept) {
        @field:Field(write = Field.Write.ALWAYS) var uuidField: KtUuidConcept? = null
        @field:Field(write = Field.Write.ALWAYS) var textField: KtStringConcept? = null
        @field:Field(write = Field.Write.ALWAYS) var longField: KtLongConcept? = null
    }

    /** Probe document for the change-stream readiness test. */
    @Document("rsr_cs_probe")
    class ChangeStreamProbe(@field:Id val id: String, val payload: String)

    /**
     * Stub class whose [Class.getSimpleName] is exactly `"Person"`, used to exercise the
     * kernel-aligned plural correction in [DefaultNamingPolicy] (`Person` → `People`).
     * Not a Spring Data entity; only its class object is passed to the naming policy.
     */
    private class Person

    // ── Tests ────────────────────────────────────────────────────────────────

    /**
     * Proves that a [SpringDataMongoChangeStreamSource] cursor opened against a real MongoDB 8.2
     * single-node replica set receives an INSERT event after a document is written. This is the
     * headline capability: change streams require replica-set mode and cannot be tested against the
     * MemoryBackend emulator.
     */
    @Test
    fun `change stream receives an INSERT event from a real MongoDB replica set`(): Unit = runBlocking {
        val dbName = "rsr-cs-${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val template = buildTemplate(dbName)
        template.createCollection(ChangeStreamProbe::class.java)

        val source = SpringDataMongoChangeStreamSource(
            MongoOperationsResolver { _ -> template },
            MongoObservationOptions(cursorAwaitTime = Duration.ofMillis(500))
        )

        source.open(ChangeStreamProbe::class.java, null, null).use { cursor ->
            template.insert(ChangeStreamProbe("probe-1", "real-value"))
            val change: MongoChange = withTimeout(30_000L) {
                var received: MongoChange? = null
                while (received == null) {
                    received = withContext(Dispatchers.IO) { cursor.next() }
                }
                received
            }
            assertEquals(MongoChangeOperation.INSERT, change.operation)
            assertEquals("rsr_cs_probe", change.collectionName)
        }
    }

    /**
     * Proves that [TenantContextMongoAccess] routes both the coroutine (`withTenant`) path and the
     * Java blocking (`TenantContextBridge`) path to genuinely isolated MongoDB databases on a real
     * server. The same document key written for two tenants must land in physically separate databases
     * and must be readable back in isolation.
     */
    @Test
    fun `TenantContextMongoAccess routes coroutine and blocking calls to isolated real databases for two tenants`(): Unit = runBlocking {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val dbA = "rsr-a-$suffix"
        val dbB = "rsr-b-$suffix"
        val templateA = buildTemplate(dbA)
        val templateB = buildTemplate(dbB)
        val access = buildAccess("tenant-a" to templateA, "tenant-b" to templateB)

        // Kotlin coroutine path: withTenant installs the tenant in the coroutine context.
        withTenant(TenantId.of("tenant-a")) {
            access.operations().save(
                KtStringRow(KtStringConcept("iso-key")).apply { textField = KtStringConcept("Tenant A") }
            )
        }
        withTenant(TenantId.of("tenant-b")) {
            access.operations().save(
                KtStringRow(KtStringConcept("iso-key")).apply { textField = KtStringConcept("Tenant B") }
            )
        }

        // Java blocking path: TenantContextBridge installs the tenant in the ThreadLocal.
        val opsA = TenantContextBridge.withTenant(TenantId.of("tenant-a"), Callable { access.operationsForCurrentTenant() })
        val opsB = TenantContextBridge.withTenant(TenantId.of("tenant-b"), Callable { access.operationsForCurrentTenant() })
        val readA = opsA.findById(KtStringConcept("iso-key"), KtStringRow::class.java)
        val readB = opsB.findById(KtStringConcept("iso-key"), KtStringRow::class.java)

        assertEquals(KtStringConcept("Tenant A"), readA?.textField)
        assertEquals(KtStringConcept("Tenant B"), readB?.textField)

        // Verify physical isolation: the two tenants have documents in separate real MongoDB databases.
        val rawA = client.getDatabase(dbA)
            .getCollection("rsr_kt_string", BsonDocument::class.java).find().first()
        val rawB = client.getDatabase(dbB)
            .getCollection("rsr_kt_string", BsonDocument::class.java).find().first()

        assertNotNull(rawA, "Tenant A's database must contain the document on the real server")
        assertNotNull(rawB, "Tenant B's database must contain the document on the real server")
        assertEquals("Tenant A", rawA!!.getString("textField").value)
        assertEquals("Tenant B", rawB!!.getString("textField").value)
    }

    /**
     * Proves that [DefaultNamingPolicy]'s kernel-aligned correction (`Person` → `People`) routes
     * writes to the correct physical collection on a real MongoDB server, not to the plain Evo
     * Inflector output `Persons`. A document written via the policy's resolved collection name must
     * be readable from `People`, and `Persons` must not exist.
     */
    @Test
    fun `DefaultNamingPolicy routes Person type to the People collection not Persons on a real MongoDB server`(): Unit {
        val policy = DefaultNamingPolicy()
        val collectionName = policy.getReadModelName(Person::class.java)
        assertEquals("People", collectionName,
            "Evo Inflector 1.3 alone gives 'Persons'; the kernel-aligned correction must produce 'People'")

        val dbName = "rsr-naming-${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val template = buildTemplate(dbName)
        val tenantId = TenantId.of("naming-tenant")
        val access = buildAccess(tenantId.value() to template)

        // Use the naming policy's resolved name via TenantContextMongoAccess.operations() to route
        // the insert through the same path a real application would use.
        access.operations(tenantId).getCollection(collectionName)
            .insertOne(org.bson.Document("_id", "probe").append("name", "Alice"))

        val rawDb = client.getDatabase(dbName)
        val found = rawDb.getCollection(collectionName).find().first()
        assertNotNull(found, "Document written to '$collectionName' must be readable from '$collectionName' on the real server")
        assertEquals("Alice", found!!.getString("name"))

        val allNames = rawDb.listCollectionNames().toList()
        assertFalse("Persons" in allNames,
            "Collection 'Persons' must not exist; the naming policy corrected it to 'People': $allNames")
    }

    /**
     * Proves that a Kotlin data class UUID concept ID is stored as BSON binary subtype 4 (standard
     * UUID) on a real MongoDB server, and that the document round-trips through Spring Data
     * correctly, preserving all field types.
     */
    @Test
    fun `UUID Kotlin concept ID persists as BSON binary subtype 4 and round-trips on a real MongoDB server`(): Unit {
        val dbName = "rsr-uuid-${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val template = buildTemplate(dbName)

        val id = KtUuidConcept(UUID.fromString("ffeeddcc-bbaa-9988-7766-554433221100"))
        val row = KtUuidRow(id).apply {
            uuidField = KtUuidConcept(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"))
            textField = KtStringConcept("stored")
            longField = KtLongConcept(9007199254740993L)
        }
        template.save(row)

        val raw = client.getDatabase(dbName)
            .getCollection("rsr_kt_uuid", BsonDocument::class.java).find().first()!!

        assertEquals(BsonType.BINARY, raw["_id"]!!.bsonType)
        assertEquals(4.toByte(), raw.getBinary("_id").type,
            "UUID concept _id must be BSON binary subtype 4 (standard UUID), not legacy subtype 3")
        assertEquals(id.value(), raw.getBinary("_id").asUuid(UuidRepresentation.STANDARD))
        assertEquals(BsonType.BINARY, raw["uuidField"]!!.bsonType)
        assertEquals(4.toByte(), raw.getBinary("uuidField").type)
        assertEquals(BsonType.STRING, raw["textField"]!!.bsonType)
        assertEquals("stored", raw.getString("textField").value)
        assertEquals(BsonType.INT64, raw["longField"]!!.bsonType)
        assertEquals(9007199254740993L, raw.getInt64("longField").value)

        val fresh = template.findById(id, KtUuidRow::class.java)!!
        assertEquals(id, fresh.id)
        assertEquals(KtUuidConcept::class.java, fresh.id.javaClass)
        assertEquals(KtUuidConcept(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")), fresh.uuidField)
        assertEquals(KtStringConcept("stored"), fresh.textField)
        assertEquals(KtLongConcept(9007199254740993L), fresh.longField)
    }

    /**
     * Proves that a 24-hex string concept ID is stored as BSON string (not promoted to ObjectId),
     * and that a Long concept ID above the JavaScript safe-integer boundary is stored as BSON int64,
     * both on a real MongoDB server.
     */
    @Test
    fun `String and Long Kotlin concept IDs persist with correct BSON types and round-trip on a real MongoDB server`(): Unit {
        val dbName = "rsr-strlong-${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val template = buildTemplate(dbName)

        // 24-hex string must remain BSON string; Spring Data must not promote it to ObjectId.
        val textId = KtStringConcept("507f1f77bcf86cd799439011")
        val textRow = KtStringRow(textId).apply { textField = KtStringConcept("text-stored") }
        template.save(textRow)
        val rawText = client.getDatabase(dbName)
            .getCollection("rsr_kt_string", BsonDocument::class.java).find().first()!!
        assertEquals(BsonType.STRING, rawText["_id"]!!.bsonType,
            "A 24-hex concept string must be stored as BSON string, not promoted to ObjectId")
        assertEquals("507f1f77bcf86cd799439011", rawText.getString("_id").value)
        val freshText = template.findById(textId, KtStringRow::class.java)!!
        assertEquals(textId, freshText.id)
        assertEquals(KtStringConcept::class.java, freshText.id.javaClass)

        // Long above the JS safe-integer limit (9007199254740993 = 2^53 + 1) must be int64.
        val longId = KtLongConcept(9007199254740993L)
        val longRow = KtLongRow(longId).apply { longField = KtLongConcept(Long.MAX_VALUE) }
        template.save(longRow)
        val rawLong = client.getDatabase(dbName)
            .getCollection("rsr_kt_long", BsonDocument::class.java).find().first()!!
        assertEquals(BsonType.INT64, rawLong["_id"]!!.bsonType,
            "Long concept ID must be stored as BSON int64")
        assertEquals(9007199254740993L, rawLong.getInt64("_id").value)
        assertEquals(BsonType.INT64, rawLong["longField"]!!.bsonType)
        assertEquals(Long.MAX_VALUE, rawLong.getInt64("longField").value)
        val freshLong = template.findById(longId, KtLongRow::class.java)!!
        assertEquals(longId, freshLong.id)
        assertEquals(KtLongConcept::class.java, freshLong.id.javaClass)
        assertEquals(KtLongConcept(Long.MAX_VALUE), freshLong.longField)
    }

    /**
     * Proves that null Kotlin concept fields annotated with `@Field(ALWAYS)` appear in BSON as
     * explicit null (not absent), that unannotated null fields are absent, and that a malformed
     * stored scalar propagates through the reading converter as a visible failure rather than being
     * silently ignored or returned as null. All on a real MongoDB server.
     */
    @Test
    fun `null Kotlin concept fields persist as BSON null and malformed scalars fail visibly on a real MongoDB server`(): Unit {
        val dbName = "rsr-null-${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val template = buildTemplate(dbName)

        // @Field(ALWAYS) fields with null value must appear as BSON null, not be absent.
        val row = KtStringRow(KtStringConcept("nullable"))
        template.save(row)
        val raw = client.getDatabase(dbName)
            .getCollection("rsr_kt_string", BsonDocument::class.java).find().first()!!
        listOf("uuidField", "textField", "longField").forEach { field ->
            assertEquals(BsonType.NULL, raw[field]!!.bsonType,
                "@Field(ALWAYS) field '$field' with null value must be BSON null, not absent from the document")
        }
        assertFalse(raw.containsKey("absentField"),
            "Field without @Field(ALWAYS) must not appear in BSON when null")

        val fresh = template.findById(row.id, KtStringRow::class.java)!!
        assertNull(fresh.uuidField)
        assertNull(fresh.textField)
        assertNull(fresh.longField)
        assertNull(fresh.absentField)

        // Inject a blank string where a non-blank KtStringConcept is expected.
        template.getCollection("rsr_kt_string").updateOne(
            org.bson.Document("_id", "nullable"),
            org.bson.Document("\$set", org.bson.Document("textField", " "))
        )
        val failure = assertThrows(RuntimeException::class.java) {
            template.findById(row.id, KtStringRow::class.java)
        }
        assertTrue(
            generateSequence<Throwable>(failure) { it.cause }.any { it.message == "Must not be blank" },
            "A malformed stored scalar must propagate through the reading converter as a visible failure, not be swallowed"
        )
    }

    // ── Companion: container lifecycle and shared utilities ──────────────────

    companion object {
        private val DEFAULT_IMAGE =
            "mongo:8.2@sha256:e0ce8c35124d4a9f9785532d1f268f39e9728ffa1cb38f46fa482436424c4bd3"

        private lateinit var container: MongoDBContainer
        private lateinit var client: com.mongodb.client.MongoClient
        private lateinit var conversions: MongoCustomConversions
        private lateinit var mappingContext: MongoMappingContext


        /**
         * Guarantees the node is an initiated replica-set member before any test runs.
         *
         * Testcontainers initiates the set itself, but that step silently does nothing against some
         * images, and the only symptom is `$changeStream` failing with error 40573 much later. This
         * check is idempotent: it initiates only when `replSetGetStatus` reports the node is not a
         * member, then waits for it to become primary.
         */
        private fun ensureReplicaSetInitiated() {
            val script = "try { if (rs.status().myState !== 1) { rs.initiate() } } " +
                "catch (e) { rs.initiate() } " +
                "for (let i = 0; i < 60; i++) { if (db.hello().isWritablePrimary) { quit(0) } sleep(500) } quit(1)"
            val result = container.execInContainer("mongosh", "--quiet", "--eval", script)
            check(result.exitCode == 0) {
                "Could not bring the MongoDB replica set to primary. " +
                    "stdout='${result.stdout}' stderr='${result.stderr}'"
            }
        }

        @JvmStatic
        @BeforeAll
        fun startContainer() {
            val image = System.getProperty("arc.mongo.replicaset.image", DEFAULT_IMAGE)
            require("@sha256:" in image) {
                "The MongoDB image must be pinned by digest ('$image' does not contain '@sha256:')."
            }
            try {
                // Pinning by digest makes the name unrecognisable to Testcontainers' compatibility check, so
                // the substitution is declared explicitly. The digest requirement above is what keeps this
                // honest: it can only ever resolve to the reviewed image.
                // withReplicaSet() is required: Testcontainers 2.x made the replica set opt-in, and
                // without it mongod starts standalone and $changeStream fails with error 40573.
                container = MongoDBContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("mongo"))
                    .withReplicaSet()
                container.start()
                ensureReplicaSetInitiated()
            } catch (throwable: Throwable) {
                if (::container.isInitialized) runCatching { container.stop() }
                throw IllegalStateException(
                    "mongoReplicaSetTest requires Docker and the pinned MongoDB image '$image'.",
                    throwable
                )
            }
            client = MongoClients.create(
                MongoClientSettings.builder()
                    // A direct connection to the mapped port is correct here: the node is an initiated
                    // replica-set member, which is what $changeStream requires. The replica-set URL would
                    // advertise the container-internal host, which is unreachable from the test JVM.
                    .applyConnectionString(ConnectionString(container.connectionString))
                    .uuidRepresentation(UuidRepresentation.STANDARD)
                    .build()
            )
            conversions = MongoCustomConversions.create { adapter ->
                adapter.registerConverter(KtUuidWrite())
                adapter.registerConverter(KtUuidRead())
                adapter.registerConverter(KtStringWrite())
                adapter.registerConverter(KtStringRead())
                adapter.registerConverter(KtLongWrite())
                adapter.registerConverter(KtLongRead())
            }
            mappingContext = MongoMappingContext()
            mappingContext.setSimpleTypeHolder(conversions.simpleTypeHolder)
            mappingContext.setInitialEntitySet(
                setOf(
                    KtUuidRow::class.java,
                    KtStringRow::class.java,
                    KtLongRow::class.java,
                    ChangeStreamProbe::class.java
                )
            )
            mappingContext.afterPropertiesSet()
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() {
            runCatching { client.close() }
            runCatching { container.stop() }
        }

        private fun buildTemplate(database: String): MongoTemplate {
            val factory = SimpleMongoClientDatabaseFactory(client, database)
            val converter = MappingMongoConverter(DefaultDbRefResolver(factory), mappingContext)
            converter.setCustomConversions(conversions)
            converter.afterPropertiesSet()
            return MongoTemplate(factory, converter)
        }

        private fun buildAccess(vararg pairs: Pair<String, MongoTemplate>): TenantContextMongoAccess {
            val map = pairs.toMap()
            val resolver = TenantAwareMongoOperationsResolver { tenantId ->
                val t = map[tenantId] ?: error("Unknown tenant '$tenantId'")
                TenantMongoOperations(tenantId, t)
            }
            return TenantContextMongoAccess(resolver, DefaultNamingPolicy())
        }
    }
}
