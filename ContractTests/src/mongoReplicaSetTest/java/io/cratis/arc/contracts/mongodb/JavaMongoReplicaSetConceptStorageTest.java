// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClients;
import io.cratis.arc.concepts.ConceptAs;
import org.bson.BsonDocument;
import org.bson.BsonType;
import org.bson.UuidRepresentation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.annotation.Id;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-server proof that Java-defined record concept types persist and round-trip correctly on a
 * real pinned MongoDB 8.2 single-node replica set.
 *
 * Counterpart to {@link MongoReplicaSetTest}, which exercises Kotlin data class concept types. This
 * class proves that Java {@code record}-based concept implementations (the natural Java idiom for
 * {@code ConceptAs<T>}) produce the same correct BSON wire types — UUID binary subtype 4, BSON
 * string, and BSON int64 — and that the null and malformed-scalar behavior is identical.
 *
 * Each test uses a unique database name so tests are independent within the shared container.
 */
final class JavaMongoReplicaSetConceptStorageTest {

    // ── Java record concept types ────────────────────────────────────────────

    record JUuidConcept(UUID value) implements ConceptAs<UUID> {
        JUuidConcept { Objects.requireNonNull(value); }
    }

    record JStringConcept(String value) implements ConceptAs<String> {
        JStringConcept {
            Objects.requireNonNull(value);
            if (value.isBlank()) throw new IllegalArgumentException("Must not be blank");
        }
    }

    record JLongConcept(Long value) implements ConceptAs<Long> {
        JLongConcept { Objects.requireNonNull(value); }
    }

    // ── Converters ──────────────────────────────────────────────────────────

    @WritingConverter
    static final class JUuidWrite implements Converter<JUuidConcept, UUID> {
        @Override public UUID convert(JUuidConcept source) { return source.value(); }
    }

    @ReadingConverter
    static final class JUuidRead implements Converter<UUID, JUuidConcept> {
        @Override public JUuidConcept convert(UUID source) { return new JUuidConcept(source); }
    }

    @WritingConverter
    static final class JStringWrite implements Converter<JStringConcept, String> {
        @Override public String convert(JStringConcept source) { return source.value(); }
    }

    @ReadingConverter
    static final class JStringRead implements Converter<String, JStringConcept> {
        @Override public JStringConcept convert(String source) { return new JStringConcept(source); }
    }

    @WritingConverter
    static final class JLongWrite implements Converter<JLongConcept, Long> {
        @Override public Long convert(JLongConcept source) { return source.value(); }
    }

    @ReadingConverter
    static final class JLongRead implements Converter<Long, JLongConcept> {
        @Override public JLongConcept convert(Long source) { return new JLongConcept(source); }
    }

    // ── Document entity types ────────────────────────────────────────────────

    @Document("rsr_j_uuid")
    static final class JUuidRow {
        @Id JUuidConcept id;
        @Field(write = Field.Write.ALWAYS) JUuidConcept uuidField;
        @Field(write = Field.Write.ALWAYS) JStringConcept textField;
        @Field(write = Field.Write.ALWAYS) JLongConcept longField;

        JUuidRow() { }
        JUuidRow(JUuidConcept id) { this.id = id; }
    }

    @Document("rsr_j_string")
    static final class JStringRow {
        @MongoId(FieldType.STRING) JStringConcept id;
        @Field(write = Field.Write.ALWAYS) JUuidConcept uuidField;
        @Field(write = Field.Write.ALWAYS) JStringConcept textField;
        @Field(write = Field.Write.ALWAYS) JLongConcept longField;
        JStringConcept absentField;

        JStringRow() { }
        JStringRow(JStringConcept id) { this.id = id; }
    }

    @Document("rsr_j_long")
    static final class JLongRow {
        @Id JLongConcept id;
        @Field(write = Field.Write.ALWAYS) JUuidConcept uuidField;
        @Field(write = Field.Write.ALWAYS) JStringConcept textField;
        @Field(write = Field.Write.ALWAYS) JLongConcept longField;

        JLongRow() { }
        JLongRow(JLongConcept id) { this.id = id; }
    }

    // ── Container lifecycle ──────────────────────────────────────────────────

    private static final String DEFAULT_IMAGE =
        "mongo:8.2@sha256:e0ce8c35124d4a9f9785532d1f268f39e9728ffa1cb38f46fa482436424c4bd3";

    private static MongoDBContainer container;
    private static com.mongodb.client.MongoClient mongoClient;
    private static MongoCustomConversions conversions;
    private static MongoMappingContext mappingContext;

    @BeforeAll
    static void startContainer() {
        var image = System.getProperty("arc.mongo.replicaset.image", DEFAULT_IMAGE);
        if (!image.contains("@sha256:")) {
            throw new IllegalArgumentException(
                "The MongoDB image must be pinned by digest ('" + image + "' does not contain '@sha256:').");
        }
        try {
            // Pinning by digest makes the name unrecognisable to Testcontainers' compatibility check, so
            // the substitution is declared explicitly; the digest requirement keeps it honest.
            // withReplicaSet() is required: Testcontainers 2.x made the replica set opt-in.
            container = new MongoDBContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("mongo"))
                .withReplicaSet();
            container.start();
        } catch (Throwable throwable) {
            if (container != null) {
                try { container.stop(); } catch (Exception ignored) { }
            }
            throw new IllegalStateException(
                "mongoReplicaSetTest requires Docker and the pinned MongoDB image '" + image + "'.",
                throwable);
        }
        mongoClient = MongoClients.create(
            MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(container.getConnectionString()))
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .build());
        conversions = MongoCustomConversions.create(adapter -> {
            adapter.registerConverter(new JUuidWrite());
            adapter.registerConverter(new JUuidRead());
            adapter.registerConverter(new JStringWrite());
            adapter.registerConverter(new JStringRead());
            adapter.registerConverter(new JLongWrite());
            adapter.registerConverter(new JLongRead());
        });
        mappingContext = new MongoMappingContext();
        mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        mappingContext.setInitialEntitySet(Set.of(JUuidRow.class, JStringRow.class, JLongRow.class));
        mappingContext.afterPropertiesSet();
    }

    @AfterAll
    static void stopContainer() {
        try { mongoClient.close(); } catch (Exception ignored) { }
        try { container.stop(); } catch (Exception ignored) { }
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    /**
     * Proves that a Java record UUID concept ID is stored as BSON binary subtype 4 (standard UUID)
     * on a real MongoDB server and that the document round-trips through Spring Data correctly.
     */
    @Test
    void javaUuidRecordConceptIdPersistsAsBsonBinarySubtype4AndRoundTripsOnARealMongoDbServer() {
        var dbName = "rsr-j-uuid-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var template = buildTemplate(dbName);

        var id = new JUuidConcept(UUID.fromString("ffeeddcc-bbaa-9988-7766-554433221100"));
        var row = new JUuidRow(id);
        row.uuidField = new JUuidConcept(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"));
        row.textField = new JStringConcept("stored");
        row.longField = new JLongConcept(9007199254740993L);
        template.save(row);

        var raw = mongoClient.getDatabase(dbName)
            .getCollection("rsr_j_uuid", BsonDocument.class).find().first();
        assertNotNull(raw);
        assertEquals(BsonType.BINARY, raw.get("_id").getBsonType());
        assertEquals((byte) 4, raw.getBinary("_id").getType(),
            "UUID concept _id must be BSON binary subtype 4 (standard UUID), not legacy subtype 3");
        assertEquals(id.value(), raw.getBinary("_id").asUuid(UuidRepresentation.STANDARD));
        assertEquals(BsonType.BINARY, raw.get("uuidField").getBsonType());
        assertEquals((byte) 4, raw.getBinary("uuidField").getType());
        assertEquals(BsonType.STRING, raw.get("textField").getBsonType());
        assertEquals("stored", raw.getString("textField").getValue());
        assertEquals(BsonType.INT64, raw.get("longField").getBsonType());
        assertEquals(9007199254740993L, raw.getInt64("longField").getValue());

        var fresh = template.findById(id, JUuidRow.class);
        assertNotNull(fresh);
        assertEquals(id, fresh.id);
        assertEquals(JUuidConcept.class, fresh.id.getClass());
        assertEquals(new JStringConcept("stored"), fresh.textField);
        assertEquals(new JLongConcept(9007199254740993L), fresh.longField);
    }

    /**
     * Proves that a Java record string concept ID is stored as BSON string (not promoted to
     * ObjectId even when the value is a 24-hex string), and that a Java record Long concept ID above
     * the JavaScript safe-integer boundary is stored as BSON int64 on a real MongoDB server.
     */
    @Test
    void javaStringAndLongRecordConceptIdsHaveCorrectBsonTypesAndRoundTripOnARealMongoDbServer() {
        var dbName = "rsr-j-sl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var template = buildTemplate(dbName);

        // 24-hex string must remain BSON string; Spring Data must not promote it to ObjectId.
        var textId = new JStringConcept("507f1f77bcf86cd799439011");
        var textRow = new JStringRow(textId);
        textRow.textField = new JStringConcept("text-value");
        template.save(textRow);
        var rawText = mongoClient.getDatabase(dbName)
            .getCollection("rsr_j_string", BsonDocument.class).find().first();
        assertNotNull(rawText);
        assertEquals(BsonType.STRING, rawText.get("_id").getBsonType(),
            "A 24-hex concept string must be stored as BSON string, not promoted to ObjectId");
        assertEquals("507f1f77bcf86cd799439011", rawText.getString("_id").getValue());
        var freshText = template.findById(textId, JStringRow.class);
        assertNotNull(freshText);
        assertEquals(textId, freshText.id);
        assertEquals(JStringConcept.class, freshText.id.getClass());

        // Long above the JS safe-integer limit (9007199254740993 = 2^53 + 1) must be int64.
        var longId = new JLongConcept(9007199254740993L);
        var longRow = new JLongRow(longId);
        longRow.longField = new JLongConcept(Long.MAX_VALUE);
        template.save(longRow);
        var rawLong = mongoClient.getDatabase(dbName)
            .getCollection("rsr_j_long", BsonDocument.class).find().first();
        assertNotNull(rawLong);
        assertEquals(BsonType.INT64, rawLong.get("_id").getBsonType(),
            "Long concept ID must be stored as BSON int64");
        assertEquals(9007199254740993L, rawLong.getInt64("_id").getValue());
        assertEquals(BsonType.INT64, rawLong.get("longField").getBsonType());
        assertEquals(Long.MAX_VALUE, rawLong.getInt64("longField").getValue());
        var freshLong = template.findById(longId, JLongRow.class);
        assertNotNull(freshLong);
        assertEquals(longId, freshLong.id);
        assertEquals(JLongConcept.class, freshLong.id.getClass());
    }

    /**
     * Proves that null Java record concept fields annotated with {@code @Field(ALWAYS)} appear as
     * BSON null (not absent), that unannotated null fields are absent, and that a malformed stored
     * scalar propagates through the reading converter as a visible failure on a real MongoDB server.
     */
    @Test
    void nullJavaRecordConceptFieldsPersistAsBsonNullAndMalformedScalarsFailVisiblyOnARealMongoDbServer() {
        var dbName = "rsr-j-null-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var template = buildTemplate(dbName);

        var row = new JStringRow(new JStringConcept("nullable"));
        template.save(row);

        var raw = mongoClient.getDatabase(dbName)
            .getCollection("rsr_j_string", BsonDocument.class).find().first();
        assertNotNull(raw);
        for (var field : new String[]{"uuidField", "textField", "longField"}) {
            assertEquals(BsonType.NULL, raw.get(field).getBsonType(),
                "@Field(ALWAYS) field '" + field + "' with null value must be BSON null, not absent from the document");
        }
        assertFalse(raw.containsKey("absentField"),
            "Field without @Field(ALWAYS) must not appear in BSON when null");

        var fresh = template.findById(row.id, JStringRow.class);
        assertNotNull(fresh);
        assertNull(fresh.uuidField);
        assertNull(fresh.textField);
        assertNull(fresh.longField);
        assertNull(fresh.absentField);

        // Inject a blank string where a non-blank JStringConcept is expected.
        template.getCollection("rsr_j_string").updateOne(
            new org.bson.Document("_id", "nullable"),
            new org.bson.Document("$set", new org.bson.Document("textField", " ")));
        assertThrows(RuntimeException.class,
            () -> template.findById(row.id, JStringRow.class),
            "A malformed stored scalar must propagate through the reading converter as a visible failure, not be swallowed");
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private static MongoTemplate buildTemplate(String database) {
        var factory = new SimpleMongoClientDatabaseFactory(mongoClient, database);
        var converter = new MappingMongoConverter(new DefaultDbRefResolver(factory), mappingContext);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        return new MongoTemplate(factory, converter);
    }
}
