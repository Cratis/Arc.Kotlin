// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.persistence.recipes.mongodb;

import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.concepts.ConceptAs;
import io.cratis.arc.springdata.mongodb.MongoReadModelForCommandResolver;
import io.cratis.arc.springdata.mongodb.TenantAwareMongoOperationsResolver;
import io.cratis.arc.springdata.mongodb.TenantMongoOperations;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonType;
import org.bson.UuidRepresentation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.annotation.Id;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

final class JavaMongoConceptStorageTests {
    public record UuidValue(UUID value) implements ConceptAs<UUID> {
        public UuidValue { Objects.requireNonNull(value); }
    }

    public record TextValue(String value) implements ConceptAs<String> {
        public TextValue {
            Objects.requireNonNull(value);
            if (value.isBlank()) throw new IllegalArgumentException("Text concept must not be blank.");
        }
    }

    public record OtherTextValue(String value) implements ConceptAs<String> { }

    public record LongValue(Long value) implements ConceptAs<Long> {
        public LongValue { Objects.requireNonNull(value); }
    }

    @WritingConverter
    public static final class UuidWrite implements Converter<UuidValue, UUID> {
        @Override
        public UUID convert(UuidValue source) { return source.value(); }
    }

    @ReadingConverter
    public static final class UuidRead implements Converter<UUID, UuidValue> {
        @Override
        public UuidValue convert(UUID source) { return new UuidValue(source); }
    }

    @WritingConverter
    public static final class TextWrite implements Converter<TextValue, String> {
        @Override
        public String convert(TextValue source) { return source.value(); }
    }

    @ReadingConverter
    public static final class TextRead implements Converter<String, TextValue> {
        @Override
        public TextValue convert(String source) { return new TextValue(source); }
    }

    @WritingConverter
    public static final class LongWrite implements Converter<LongValue, Long> {
        @Override
        public Long convert(LongValue source) { return source.value(); }
    }

    @ReadingConverter
    public static final class LongRead implements Converter<Long, LongValue> {
        @Override
        public LongValue convert(Long source) { return new LongValue(source); }
    }

    public static class Fields {
        @Field(write = Field.Write.ALWAYS)
        public UuidValue uuidField;
        @Field(write = Field.Write.ALWAYS)
        public TextValue textField;
        @Field(write = Field.Write.ALWAYS)
        public LongValue longField;
        public TextValue absentField;
    }

    @ReadModel
    @Document("j_uuid")
    public static final class UuidRow extends Fields {
        @Id
        public UuidValue id;
        public UuidRow() { }
        public UuidRow(UuidValue id) { this.id = id; }
    }

    @ReadModel
    @Document("j_text")
    public static final class TextRow extends Fields {
        @MongoId(FieldType.STRING)
        public TextValue id;
        public TextRow() { }
        public TextRow(TextValue id) { this.id = id; }
    }

    @ReadModel
    @Document("j_long")
    public static final class LongRow extends Fields {
        @Id
        public LongValue id;
        public LongRow() { }
        public LongRow(LongValue id) { this.id = id; }
    }

    @Document("j_missing")
    public record MissingRow(@MongoId String id, TextValue textField) { }

    public interface UuidRows extends MongoRepository<UuidRow, UuidValue> {
        List<UuidRow> findByTextField(TextValue value);
    }

    public interface TextRows extends MongoRepository<TextRow, TextValue> { }
    public interface LongRows extends MongoRepository<LongRow, LongValue> { }

    private static MongoCustomConversions conversions() {
        return MongoCustomConversions.create(adapter -> {
            adapter.registerConverter(new UuidWrite());
            adapter.registerConverter(new UuidRead());
            adapter.registerConverter(new TextWrite());
            adapter.registerConverter(new TextRead());
            adapter.registerConverter(new LongWrite());
            adapter.registerConverter(new LongRead());
        });
    }

    private static RecipeMongoStore store() {
        return new RecipeMongoStore(Set.of(UuidRow.class, TextRow.class, LongRow.class), conversions());
    }

    private static <T extends Fields> T populated(T row, String text) {
        row.uuidField = new UuidValue(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"));
        row.textField = new TextValue(text);
        row.longField = new LongValue(9007199254740993L);
        return row;
    }

    private static void assertFields(Fields expected, Fields actual) {
        assertNotSame(expected, actual);
        assertEquals(expected.getClass(), actual.getClass());
        assertEquals(expected.uuidField, actual.uuidField);
        assertEquals(expected.textField, actual.textField);
        assertEquals(expected.longField, actual.longField);
        assertEquals(UuidValue.class, actual.uuidField.getClass());
        assertEquals(TextValue.class, actual.textField.getClass());
        assertEquals(LongValue.class, actual.longField.getClass());
    }

    private static void assertRawFields(BsonDocument raw, Fields row) {
        assertEquals(BsonType.BINARY, raw.get("uuidField").getBsonType());
        assertEquals((byte) 4, raw.getBinary("uuidField").getType());
        assertEquals(row.uuidField.value(), raw.getBinary("uuidField").asUuid(UuidRepresentation.STANDARD));
        assertEquals(BsonType.STRING, raw.get("textField").getBsonType());
        assertEquals(row.textField.value(), raw.getString("textField").getValue());
        assertEquals(BsonType.INT64, raw.get("longField").getBsonType());
        assertEquals(row.longField.value().longValue(), raw.getInt64("longField").getValue());
    }

    @Test
    void uuidConceptIdAndFieldsRoundTripThroughRepositoryAndConceptPredicates() {
        try (var store = store()) {
            var id = new UuidValue(UUID.fromString("ffeeddcc-bbaa-9988-7766-554433221100"));
            var row = populated(new UuidRow(id), "Stored");
            var template = store.template("primary");
            var repository = new MongoRepositoryFactory(template).getRepository(UuidRows.class);
            repository.save(row);
            var raw = store.raw("primary", "j_uuid");
            assertEquals((byte) 4, raw.getBinary("_id").getType());
            assertEquals(id.value(), raw.getBinary("_id").asUuid(UuidRepresentation.STANDARD));
            assertRawFields(raw, row);
            assertFields(row, repository.findById(new UuidValue(id.value())).orElseThrow());
            assertEquals(List.of(id), repository.findByTextField(new TextValue("Stored")).stream().map(found -> found.id).toList());
            assertEquals(id, template.findOne(query(where("uuidField").is(row.uuidField)), UuidRow.class).id);
            assertEquals(id, template.findOne(query(where("longField").is(row.longField)), UuidRow.class).id);
            var fresh = store.template("primary").findById(id, UuidRow.class);
            assertEquals(id, fresh.id);
            assertEquals(UuidValue.class, fresh.id.getClass());
            assertFields(row, fresh);
        }
    }

    @Test
    void twentyFourHexStringConceptIdStaysBsonStringAndFindsByConceptKey() {
        try (var store = store()) {
            var id = new TextValue("507f1f77bcf86cd799439011");
            var row = populated(new TextRow(id), "Stored");
            var repository = new MongoRepositoryFactory(store.template("primary")).getRepository(TextRows.class);
            repository.save(row);
            var raw = store.raw("primary", "j_text");
            assertEquals(BsonType.STRING, raw.get("_id").getBsonType());
            assertEquals(id.value(), raw.getString("_id").getValue());
            assertRawFields(raw, row);
            assertEquals(id, repository.findById(new TextValue(id.value())).orElseThrow().id);
            var fresh = store.template("primary").findById(id, TextRow.class);
            assertEquals(TextValue.class, fresh.id.getClass());
            assertFields(row, fresh);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -9007199254740993L, 9007199254740993L, Long.MAX_VALUE})
    void longConceptIdsAndFieldsPreserveSigned64BitValues(long value) {
        try (var store = store()) {
            var id = new LongValue(value);
            var row = populated(new LongRow(id), "Stored");
            row.longField = id;
            var repository = new MongoRepositoryFactory(store.template("primary")).getRepository(LongRows.class);
            repository.save(row);
            var raw = store.raw("primary", "j_long");
            assertEquals(BsonType.INT64, raw.get("_id").getBsonType());
            assertEquals(value, raw.getInt64("_id").getValue());
            assertRawFields(raw, row);
            assertEquals(id, repository.findById(new LongValue(value)).orElseThrow().id);
            var fresh = store.template("primary").findById(id, LongRow.class);
            assertEquals(LongValue.class, fresh.id.getClass());
            assertFields(row, fresh);
            assertEquals(id, store.template("primary").findOne(query(where("longField").is(id)), LongRow.class).id);
        }
    }

    @Test
    void explicitNullFieldsAndAbsentFieldsHaveDistinctBsonShapesAndReadAsNull() {
        try (var store = store()) {
            var row = new TextRow(new TextValue("nullable"));
            store.template("primary").save(row);
            var raw = store.raw("primary", "j_text");
            for (var field : List.of("uuidField", "textField", "longField")) {
                assertEquals(BsonType.NULL, raw.get(field).getBsonType());
            }
            assertFalse(raw.containsKey("absentField"));
            var fresh = store.template("primary").findById(row.id, TextRow.class);
            assertNull(fresh.uuidField);
            assertNull(fresh.textField);
            assertNull(fresh.longField);
            assertNull(fresh.absentField);
            store.template("primary").getCollection("j_text").updateOne(
                new org.bson.Document("_id", "nullable"), new org.bson.Document("$unset", new org.bson.Document("textField", "")));
            assertFalse(store.raw("primary", "j_text").containsKey("textField"));
            assertNull(store.template("primary").findById(row.id, TextRow.class).textField);
        }
    }

    @Test
    void replacementRequiresExplicitSaveAndMappedUpdateBindsConceptValues() {
        try (var store = store()) {
            var row = populated(new TextRow(new TextValue("replace")), "Stored");
            var template = store.template("primary");
            var repository = new MongoRepositoryFactory(template).getRepository(TextRows.class);
            repository.save(row);
            var loaded = repository.findById(row.id).orElseThrow();
            loaded.textField = new TextValue("Saved");
            assertEquals(new TextValue("Stored"), store.template("primary").findById(row.id, TextRow.class).textField);
            repository.save(loaded);
            assertEquals(new TextValue("Saved"), store.template("primary").findById(row.id, TextRow.class).textField);
            var update = template.updateFirst(query(where("id").is(row.id)), new Update().set("longField", new LongValue(Long.MIN_VALUE)), TextRow.class);
            assertEquals(1L, update.getModifiedCount());
            assertEquals(Long.MIN_VALUE, store.raw("primary", "j_text").getInt64("longField").getValue());
            assertEquals(new LongValue(Long.MIN_VALUE), store.template("primary").findById(row.id, TextRow.class).longField);
        }
    }

    @Test
    void malformedStoredScalarFailsVisiblyWithoutDefaultConcept() {
        try (var store = store()) {
            var row = populated(new TextRow(new TextValue("invalid")), "Stored");
            var template = store.template("primary");
            template.save(row);
            template.getCollection("j_text").updateOne(
                new org.bson.Document("_id", "invalid"), new org.bson.Document("$set", new org.bson.Document("textField", " ")));
            var failure = assertThrows(RuntimeException.class, () -> store.template("primary").findById(row.id, TextRow.class));
            assertTrue(Stream.iterate((Throwable) failure, Objects::nonNull, Throwable::getCause)
                .anyMatch(cause -> "Text concept must not be blank.".equals(cause.getMessage())));
            template.getCollection("j_text").updateOne(
                new org.bson.Document("_id", "invalid"), new org.bson.Document("$set", new org.bson.Document("textField", "Valid").append("longField", "not-a-long")));
            assertEquals("not-a-long", store.raw("primary", "j_text").getString("longField").getValue());
            var numericFailure = assertThrows(ConverterNotFoundException.class, () -> store.template("primary").findById(row.id, TextRow.class));
            assertEquals(String.class, numericFailure.getSourceType().getType());
            assertEquals(LongValue.class, numericFailure.getTargetType().getType());
        }
    }

    @Test
    void missingApplicationMappingIsOmissionControlNotArcDefect() {
        try (var store = new RecipeMongoStore(Set.of(MissingRow.class), MongoCustomConversions.create(adapter -> { }))) {
            store.template("primary").save(new MissingRow("missing", new TextValue("Stored")));
            var raw = store.raw("primary", "j_missing");
            assertEquals(BsonType.DOCUMENT, raw.get("textField").getBsonType());
            assertEquals("Stored", raw.getDocument("textField").getString("value").getValue());
        }
        try (var store = new RecipeMongoStore(Set.of(MissingRow.class), conversions())) {
            store.template("primary").save(new MissingRow("missing", new TextValue("Stored")));
            var raw = store.raw("primary", "j_missing");
            assertEquals(BsonType.STRING, raw.get("textField").getBsonType());
            assertEquals("Stored", raw.getString("textField").getValue());
        }
    }

    @Test
    void certifiedDatabasesIsolateSameConceptKeysAndRejectInvalidRoutingWithoutRetry() {
        try (var store = store()) {
            var a = store.template("tenant_a");
            var b = store.template("tenant_b");
            var uuid = new UuidValue(UUID.randomUUID());
            var text = new TextValue("507f1f77bcf86cd799439011");
            var number = new LongValue(Long.MAX_VALUE);
            a.save(populated(new UuidRow(uuid), "A"));
            b.save(populated(new UuidRow(uuid), "B"));
            a.save(populated(new TextRow(text), "A"));
            b.save(populated(new TextRow(text), "B"));
            a.save(populated(new LongRow(number), "A"));
            b.save(populated(new LongRow(number), "B"));
            store.clearFindDatabases();
            var calls = new ArrayList<String>();
            TenantAwareMongoOperationsResolver resolver = tenant -> {
                calls.add(tenant);
                return switch (tenant) {
                    case "a" -> new TenantMongoOperations("a", a);
                    case "b" -> new TenantMongoOperations("b", b);
                    case "mismatch" -> new TenantMongoOperations("a", a);
                    default -> throw new IllegalArgumentException("Unknown tenant " + tenant);
                };
            };
            var provider = new MongoReadModelForCommandResolver(store.mappingContext(), resolver, true);
            assertEquals(Set.of(UuidRow.class, TextRow.class, LongRow.class), provider.readModelTypes());
            assertTenantValues(provider, UuidRow.class, uuid);
            assertTenantValues(provider, TextRow.class, text);
            assertTenantValues(provider, LongRow.class, number);
            assertEquals(List.of("a", "b", "a", "b", "a", "b"), calls);
            assertThrows(IllegalArgumentException.class, () -> provider.resolveBlocking(TextRow.class, context("unknown"), text));
            assertThrows(IllegalStateException.class, () -> provider.resolveBlocking(TextRow.class, context("mismatch"), text));
            assertThrows(IllegalArgumentException.class, () -> provider.resolveBlocking(TextRow.class, context("a"), text.value()));
            assertThrows(IllegalArgumentException.class, () -> provider.resolveBlocking(TextRow.class, context("a"), number));
            assertThrows(IllegalArgumentException.class, () -> provider.resolveBlocking(TextRow.class, context("a"), new OtherTextValue(text.value())));
            assertThrows(IllegalArgumentException.class, () -> provider.resolveBlocking(TextRow.class, context(null), text));
            assertThrows(IllegalArgumentException.class, () -> provider.resolveBlocking(TextRow.class, context(" "), text));
            assertEquals(List.of("a", "b", "a", "b", "a", "b", "unknown", "mismatch"), calls);
            assertEquals(List.of("tenant_a", "tenant_b", "tenant_a", "tenant_b", "tenant_a", "tenant_b"), store.findDatabases());
        }
    }

    private static void assertTenantValues(MongoReadModelForCommandResolver provider, Class<?> type, Object key) {
        assertEquals(new TextValue("A"), ((Fields) provider.resolveBlocking(type, context("a"), key)).textField);
        assertEquals(new TextValue("B"), ((Fields) provider.resolveBlocking(type, context("b"), key)).textField);
    }

    private static CommandContext context(String tenant) {
        return new CommandContext(UUID.randomUUID(), new Object(), Object.class, ArcPrincipal.anonymous(), tenant,
            null, null, new ServiceResolver() {
                @Override
                public <T> T resolve(Class<T> type) { return null; }
            });
    }
}
