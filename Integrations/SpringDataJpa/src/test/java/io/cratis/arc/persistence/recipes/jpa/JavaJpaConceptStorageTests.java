// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.persistence.recipes.jpa;

import io.cratis.arc.artifacts.ReadModel;
import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.commands.CommandKeyProvider;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.concepts.ConceptAs;
import io.cratis.arc.springdata.jpa.JpaPersistenceUnit;
import io.cratis.arc.springdata.jpa.JpaPersistenceUnitResolver;
import io.cratis.arc.springdata.jpa.JpaReadModelForCommandResolver;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.Type;
import java.io.Serializable;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.RowCallbackHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JavaJpaConceptStorageTests {
    private static final UUID UUID_VALUE = UUID.fromString("e980bb77-0727-4b8a-986d-576a826cb430");
    private static final UUID OTHER_UUID = UUID.fromString("c17b2970-3c90-4936-8a94-ff38e2328563");

    @Test
    void recordEmbeddedUuidIdExposesOneMetamodelIdentifierAndResolvesThroughCertifiedArcUnit() {
        try (var store = store()) {
            var id = new UuidId(UUID_VALUE);
            store.transaction(manager -> {
                manager.persist(new UuidRow(id, "Stored"));
                manager.flush();
                manager.clear();
                assertEquals(id, manager.find(UuidRow.class, new UuidId(UUID_VALUE)).id);
            });
            assertIdentifier(store, UuidRow.class, UuidId.class);
            var unit = JpaPersistenceUnit.builder(store.factory()).tenant("one", "recipes").build();
            var provider = new JpaReadModelForCommandResolver(new Units(unit), true);
            var loaded = (UuidRow) resolve(provider, UuidRow.class, new UuidId(UUID_VALUE), "one", "recipes");
            assertEquals("Stored", loaded.label);
            assertEquals(id, loaded.id);
            assertEquals(id.hashCode(), loaded.id.hashCode());
        }
    }

    @Test
    void concreteRecordConvertersBindPredicatesAndDetectManagedReplacementWithoutResave() {
        try (var store = store()) {
            var id = new UuidId(UUID_VALUE);
            var original = new UuidRow(id, "Original");
            original.uuidField = new UuidValue(UUID_VALUE);
            original.textField = new TextValue("Initial");
            original.numberField = new LongValue(Long.MIN_VALUE);
            store.transaction(manager -> {
                var repository = new JpaRepositoryFactory(manager).getRepository(UuidRepository.class);
                repository.saveAndFlush(original);
                manager.clear();
                var loaded = repository.findById(new UuidId(UUID_VALUE)).orElseThrow();
                assertNotSame(original, loaded);
                assertEquals(original.uuidField, loaded.uuidField);
                assertEquals(original.textField, loaded.textField);
                assertEquals(original.numberField, loaded.numberField);
                assertEquals(List.of(id), repository.findByUuidField(new UuidValue(UUID_VALUE)).stream().map(row -> row.id).toList());
                assertEquals(List.of(id), repository.findByTextField(new TextValue("Initial")).stream().map(row -> row.id).toList());
                assertEquals(List.of(id), repository.findByNumberField(new LongValue(Long.MIN_VALUE)).stream().map(row -> row.id).toList());
            });
            assertRaw(store, "j_concept_uuid", UUID_VALUE, UUID_VALUE, "Initial", Long.MIN_VALUE);
            store.transaction(manager -> {
                var managed = manager.find(UuidRow.class, new UuidId(UUID_VALUE));
                managed.uuidField = new UuidValue(OTHER_UUID);
                managed.textField = new TextValue("Replacement");
                managed.numberField = new LongValue(Long.MAX_VALUE);
                manager.flush(); // Deliberately no repository.save/merge after replacement.
                manager.clear();
                var loaded = manager.find(UuidRow.class, new UuidId(UUID_VALUE));
                assertNotSame(managed, loaded);
                assertEquals(new UuidValue(OTHER_UUID), loaded.uuidField);
                assertEquals(new TextValue("Replacement"), loaded.textField);
                assertEquals(new LongValue(Long.MAX_VALUE), loaded.numberField);
            });
            assertRaw(store, "j_concept_uuid", UUID_VALUE, OTHER_UUID, "Replacement", Long.MAX_VALUE);
            store.read(manager -> {
                var repository = new JpaRepositoryFactory(manager).getRepository(UuidRepository.class);
                assertTrue(repository.findByTextField(new TextValue("Initial")).isEmpty());
                assertEquals(List.of(id), repository.findByUuidField(new UuidValue(OTHER_UUID)).stream().map(row -> row.id).toList());
                assertEquals(List.of(id), repository.findByTextField(new TextValue("Replacement")).stream().map(row -> row.id).toList());
                assertEquals(List.of(id), repository.findByNumberField(new LongValue(Long.MAX_VALUE)).stream().map(row -> row.id).toList());
                return null;
            });
        }
    }

    @Test
    void stringRecordEmbeddedIdStaysVarcharAndNullableFieldsSurviveReloadAndReplacement() {
        try (var store = store()) {
            var scalar = "0123456789abcdef01234567";
            var id = new StringId(scalar);
            store.transaction(manager -> {
                var repository = new JpaRepositoryFactory(manager).getRepository(StringRepository.class);
                repository.saveAndFlush(new StringRow(id, "Nullable"));
                manager.clear();
                var loaded = repository.findById(new StringId(scalar)).orElseThrow();
                assertEquals(id, loaded.id);
                assertEquals(id.hashCode(), loaded.id.hashCode());
                assertNull(loaded.uuidField);
                assertNull(loaded.textField);
                assertNull(loaded.numberField);
                loaded.uuidField = new UuidValue(UUID_VALUE);
                loaded.textField = new TextValue("Non-null");
                loaded.numberField = new LongValue(0L);
            });
            assertIdentifier(store, StringRow.class, StringId.class);
            assertRaw(store, "j_concept_string", scalar, UUID_VALUE, "Non-null", 0L);
            store.transaction(manager -> {
                var loaded = manager.find(StringRow.class, new StringId(scalar));
                assertEquals(new TextValue("Non-null"), loaded.textField);
                loaded.uuidField = null;
                loaded.textField = null;
                loaded.numberField = null;
                manager.flush();
                manager.clear();
                var empty = manager.find(StringRow.class, new StringId(scalar));
                assertNull(empty.uuidField);
                assertNull(empty.textField);
                assertNull(empty.numberField);
            });
            assertRaw(store, "j_concept_string", scalar, null, null, null);
            store.read(manager -> {
                var loaded = new JpaRepositoryFactory(manager).getRepository(StringRepository.class)
                    .findById(new StringId(scalar)).orElseThrow();
                assertNull(loaded.uuidField);
                assertNull(loaded.textField);
                assertNull(loaded.numberField);
                return null;
            });
            var provider = new JpaReadModelForCommandResolver(new Units(
                JpaPersistenceUnit.builder(store.factory()).tenant("one", "recipes").build()), true);
            assertEquals(id, ((StringRow) resolve(provider, StringRow.class, new StringId(scalar), "one", "recipes")).id);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -9007199254740993L, 0L, 9007199254740993L, Long.MAX_VALUE})
    void longRecordIdsAndAttributesRetainSigned64BitValues(long scalar) {
        try (var store = store()) {
            var id = new LongId(scalar);
            store.transaction(manager -> {
                var repository = new JpaRepositoryFactory(manager).getRepository(LongRepository.class);
                var row = new LongRow(id, "Exact");
                row.numberField = new LongValue(scalar);
                repository.saveAndFlush(row);
                manager.clear();
                var loaded = repository.findById(new LongId(scalar)).orElseThrow();
                assertEquals(id, loaded.id);
                assertEquals(id.hashCode(), loaded.id.hashCode());
                assertEquals(new LongValue(scalar), loaded.numberField);
                assertEquals(List.of(id), repository.findByNumberField(new LongValue(scalar)).stream().map(value -> value.id).toList());
            });
            assertIdentifier(store, LongRow.class, LongId.class);
            assertRaw(store, "j_concept_long", scalar, null, null, scalar);
            var provider = new JpaReadModelForCommandResolver(new Units(
                JpaPersistenceUnit.builder(store.factory()).tenant("one", "recipes").build()), true);
            assertEquals(id, ((LongRow) resolve(provider, LongRow.class, new LongId(scalar), "one", "recipes")).id);
        }
    }

    @Test
    void malformedStoredConceptsFailConstructionAndInvalidSqlScalarsAreRejected() {
        try (var store = store()) {
            store.transaction(manager -> manager.persist(new UuidRow(new UuidId(UUID_VALUE), "Invalid storage")));
            store.jdbc().update("update j_concept_uuid set text_field = ''");
            var textFailure = assertThrows(RuntimeException.class,
                () -> store.read(manager -> manager.find(UuidRow.class, new UuidId(UUID_VALUE))));
            assertTrue(causes(textFailure).anyMatch(cause -> cause instanceof IllegalArgumentException
                && "Text concept must not be blank.".equals(cause.getMessage())));
            store.jdbc().update("update j_concept_uuid set text_field = null, uuid_field = ?", new UUID(0, 0));
            var uuidFailure = assertThrows(RuntimeException.class,
                () -> store.read(manager -> manager.find(UuidRow.class, new UuidId(UUID_VALUE))));
            assertTrue(causes(uuidFailure).anyMatch(cause -> cause instanceof IllegalArgumentException
                && "UUID concept must not be nil.".equals(cause.getMessage())));
            for (var sql : List.of(
                "update j_concept_uuid set uuid_field = 'not-a-uuid'",
                "update j_concept_uuid set number_field = '9223372036854775808'")) {
                var failure = assertThrows(DataAccessException.class, () -> store.jdbc().update(sql));
                assertTrue(causes(failure).anyMatch(cause -> cause instanceof SQLException error
                    && error.getSQLState().startsWith("22")));
            }
        }
    }

    @Test
    void missingExplicitFieldMappingFailsFactoryCreationDespiteRegisteredNonAutoApplyingConverter() {
        // Application recipe omission control, not an existing Arc product defect.
        var failure = assertThrows(RuntimeException.class, () -> {
            try (var store = new RecipeJpaStore(UnconvertedRow.class, TextConverter.class)) {
                store.factory();
            }
        });
        assertTrue(causes(failure).anyMatch(cause -> cause.getMessage() != null
            && cause.getMessage().contains("Could not determine recommended JdbcType")
            && cause.getMessage().contains(TextValue.class.getName())));
    }

    @Test
    void sameConceptKeysAreTenantIsolatedAndBadKeysCertificatesAndNamespacesNeverRetryFixedStorage() {
        try (var first = store(); var second = store()) {
            for (var pair : List.of(new StoreLabel(first, "First"), new StoreLabel(second, "Second"))) {
                pair.store.transaction(manager -> {
                    var uuid = new UuidRow(new UuidId(UUID_VALUE), pair.label);
                    var text = new StringRow(new StringId("same-key"), pair.label);
                    var number = new LongRow(new LongId(Long.MIN_VALUE), pair.label);
                    for (var row : List.of(uuid, text, number)) {
                        row.textField = new TextValue(pair.label);
                        manager.persist(row);
                    }
                });
            }
            var firstUnit = JpaPersistenceUnit.builder(first.factory()).tenant("one", "recipes").build();
            var units = new Units(firstUnit, JpaPersistenceUnit.builder(second.factory()).tenant("two", "recipes").build());
            var provider = new JpaReadModelForCommandResolver(units, true);
            var cases = List.of(new EntityKey(UuidRow.class, new UuidId(UUID_VALUE)),
                new EntityKey(StringRow.class, new StringId("same-key")), new EntityKey(LongRow.class, new LongId(Long.MIN_VALUE)));
            for (var item : cases) {
                assertEquals(new TextValue("First"), ((Fields) resolve(provider, item.type, item.key, "one", "recipes")).textField);
                assertEquals(new TextValue("Second"), ((Fields) resolve(provider, item.type, item.key, "two", "recipes")).textField);
                units.calls.clear();
                var wrongKey = assertThrows(IllegalArgumentException.class,
                    () -> resolve(provider, item.type, item.key.value(), "one", "recipes"));
                assertTrue(wrongKey.getMessage().contains("must be an instance of"));
                assertEquals(List.of(new Tenant("one", "recipes")), units.calls);
                for (var tenant : List.of(new Tenant("unknown", "recipes"), new Tenant("one", "wrong"))) {
                    units.calls.clear();
                    var failure = assertThrows(IllegalStateException.class,
                        () -> resolve(provider, item.type, item.key, tenant.id, tenant.namespace));
                    assertTrue(failure.getMessage().contains("No JPA persistence unit"));
                    assertEquals(List.of(tenant), units.calls);
                }
                units.calls.clear();
                assertThrows(IllegalArgumentException.class, () -> resolve(provider, item.type, item.key, null, "recipes"));
                assertTrue(units.calls.isEmpty());
                var mismatchCalls = new ArrayList<Tenant>();
                var mismatch = new JpaReadModelForCommandResolver(new JpaPersistenceUnitResolver() {
                    @Override
                    public Set<Class<?>> readModelTypes() { return firstUnit.readModelTypes(); }
                    @Override
                    public JpaPersistenceUnit resolve(String tenantId, String tenantNamespace) {
                        mismatchCalls.add(new Tenant(tenantId, tenantNamespace));
                        return firstUnit;
                    }
                }, true);
                var failure = assertThrows(IllegalStateException.class,
                    () -> resolve(mismatch, item.type, item.key, "two", "recipes"));
                assertTrue(failure.getMessage().contains("certificate does not match"));
                assertEquals(List.of(new Tenant("two", "recipes")), mismatchCalls);
            }
            units.calls.clear();
            assertThrows(IllegalArgumentException.class,
                () -> resolve(provider, UuidRow.class, new UuidValue(UUID_VALUE), "one", "recipes"));
            assertEquals(List.of(new Tenant("one", "recipes")), units.calls);
            assertNull(resolve(provider, UuidRow.class, new UuidId(OTHER_UUID), "one", "recipes"));
        }
    }

    private static RecipeJpaStore store() {
        return new RecipeJpaStore(UuidRow.class, StringRow.class, LongRow.class, Fields.class,
            UuidId.class, StringId.class, LongId.class, UuidConverter.class, TextConverter.class, LongConverter.class);
    }

    private static void assertIdentifier(RecipeJpaStore store, Class<?> entity, Class<?> identifier) {
        var model = store.factory().getMetamodel().entity(entity);
        assertTrue(model.hasSingleIdAttribute());
        assertEquals(identifier, model.getIdType().getJavaType());
        assertEquals(Type.PersistenceType.EMBEDDABLE, model.getIdType().getPersistenceType());
        assertEquals(1, store.factory().getMetamodel().embeddable(identifier).getAttributes().size());
    }

    private static void assertRaw(RecipeJpaStore store, String table, Object id, UUID uuid, String text, Long number) {
        store.jdbc().query("select concept_id, uuid_field, text_field, number_field from " + table, (RowCallbackHandler) result -> {
            assertEquals(id, result.getObject(1));
            assertEquals(uuid, result.getObject(2));
            assertEquals(text, result.getObject(3));
            assertEquals(number, result.getObject(4));
            assertEquals(id instanceof UUID ? "UUID" : id instanceof Long ? "BIGINT" : "CHARACTER VARYING", result.getMetaData().getColumnTypeName(1));
            assertEquals("UUID", result.getMetaData().getColumnTypeName(2));
            assertEquals("CHARACTER VARYING", result.getMetaData().getColumnTypeName(3));
            assertEquals("BIGINT", result.getMetaData().getColumnTypeName(4));
        });
        assertEquals(1L, store.jdbc().queryForObject("select count(*) from " + table, Long.class));
        assertEquals(5L, store.jdbc().queryForObject(
            "select count(*) from information_schema.columns where table_name = ?",
            Long.class, table.toUpperCase(java.util.Locale.ROOT)));
    }

    private static Object resolve(JpaReadModelForCommandResolver provider, Class<?> type, Object key, String tenant, String namespace) {
        var command = new KeyCommand(key);
        var context = new CommandContext(UUID.randomUUID(), command, KeyCommand.class, ArcPrincipal.anonymous(), tenant, namespace,
            new ServiceResolver() {
                @Override
                public <T> T resolve(Class<T> serviceType) { return null; }
            });
        assertEquals(key, context.getCommandKey());
        return provider.resolveBlocking(type, context, key);
    }

    private static Stream<Throwable> causes(Throwable failure) {
        return Stream.iterate(failure, Objects::nonNull, Throwable::getCause);
    }

    private record KeyCommand(Object key) implements CommandKeyProvider {
        @Override
        public Object commandKey() { return key; }
    }
    private record Tenant(String id, String namespace) { }
    private record EntityKey(Class<?> type, ConceptAs<?> key) { }
    private record StoreLabel(RecipeJpaStore store, String label) { }

    private static final class Units implements JpaPersistenceUnitResolver {
        private final List<JpaPersistenceUnit> units;
        private final JpaPersistenceUnit fixed;
        private final List<Tenant> calls = new ArrayList<>();
        private Units(JpaPersistenceUnit... units) {
            this.units = Arrays.asList(units);
            fixed = JpaPersistenceUnit.fixed(units[0].getEntityManagerFactory());
        }
        @Override
        public Set<Class<?>> readModelTypes() {
            var types = new LinkedHashSet<Class<?>>();
            units.forEach(unit -> types.addAll(unit.readModelTypes()));
            return Set.copyOf(types);
        }
        @Override
        public JpaPersistenceUnit resolve(String tenantId, String tenantNamespace) {
            calls.add(new Tenant(tenantId, tenantNamespace));
            if (tenantId == null && tenantNamespace == null) return fixed;
            return units.stream().filter(unit -> Objects.equals(unit.getTenantId(), tenantId)
                && Objects.equals(unit.getTenantNamespace(), tenantNamespace)).findFirst().orElse(null);
        }
    }

    @Embeddable
    public record UuidId(UUID value) implements ConceptAs<UUID>, Serializable { }
    @Embeddable
    public record StringId(String value) implements ConceptAs<String>, Serializable { }
    @Embeddable
    public record LongId(Long value) implements ConceptAs<Long>, Serializable { }

    public record UuidValue(UUID value) implements ConceptAs<UUID> {
        public UuidValue {
            Objects.requireNonNull(value);
            if (value.equals(new UUID(0, 0))) throw new IllegalArgumentException("UUID concept must not be nil.");
        }
    }
    public record TextValue(String value) implements ConceptAs<String> {
        public TextValue {
            Objects.requireNonNull(value);
            if (value.isBlank()) throw new IllegalArgumentException("Text concept must not be blank.");
        }
    }
    public record LongValue(Long value) implements ConceptAs<Long> {
        public LongValue { Objects.requireNonNull(value); }
    }

    @Converter(autoApply = false)
    public static class UuidConverter implements AttributeConverter<UuidValue, UUID> {
        @Override
        public UUID convertToDatabaseColumn(UuidValue attribute) { return attribute == null ? null : attribute.value(); }
        @Override
        public UuidValue convertToEntityAttribute(UUID dbData) { return dbData == null ? null : new UuidValue(dbData); }
    }
    @Converter(autoApply = false)
    public static class TextConverter implements AttributeConverter<TextValue, String> {
        @Override
        public String convertToDatabaseColumn(TextValue attribute) { return attribute == null ? null : attribute.value(); }
        @Override
        public TextValue convertToEntityAttribute(String dbData) { return dbData == null ? null : new TextValue(dbData); }
    }
    @Converter(autoApply = false)
    public static class LongConverter implements AttributeConverter<LongValue, Long> {
        @Override
        public Long convertToDatabaseColumn(LongValue attribute) { return attribute == null ? null : attribute.value(); }
        @Override
        public LongValue convertToEntityAttribute(Long dbData) { return dbData == null ? null : new LongValue(dbData); }
    }

    @MappedSuperclass
    public static class Fields {
        @Convert(converter = UuidConverter.class)
        @Column(name = "uuid_field")
        public UuidValue uuidField;
        @Convert(converter = TextConverter.class)
        @Column(name = "text_field")
        public TextValue textField;
        @Convert(converter = LongConverter.class)
        @Column(name = "number_field")
        public LongValue numberField;
    }

    @Entity(name = "JavaConceptUuidRow")
    @Table(name = "j_concept_uuid")
    @ReadModel
    public static class UuidRow extends Fields {
        @EmbeddedId
        @AttributeOverride(name = "value", column = @Column(name = "concept_id"))
        public UuidId id;
        public String label;
        public UuidRow() { }
        public UuidRow(UuidId id, String label) { this.id = id; this.label = label; }
    }
    @Entity(name = "JavaConceptStringRow")
    @Table(name = "j_concept_string")
    @ReadModel
    public static class StringRow extends Fields {
        @EmbeddedId
        @AttributeOverride(name = "value", column = @Column(name = "concept_id"))
        public StringId id;
        public String label;
        public StringRow() { }
        public StringRow(StringId id, String label) { this.id = id; this.label = label; }
    }
    @Entity(name = "JavaConceptLongRow")
    @Table(name = "j_concept_long")
    @ReadModel
    public static class LongRow extends Fields {
        @EmbeddedId
        @AttributeOverride(name = "value", column = @Column(name = "concept_id"))
        public LongId id;
        public String label;
        public LongRow() { }
        public LongRow(LongId id, String label) { this.id = id; this.label = label; }
    }

    @Entity(name = "JavaUnconvertedConceptRow")
    public static class UnconvertedRow {
        @Id
        public String id;
        public TextValue textField;
    }

    public interface UuidRepository extends JpaRepository<UuidRow, UuidId> {
        List<UuidRow> findByUuidField(UuidValue value);
        List<UuidRow> findByTextField(TextValue value);
        List<UuidRow> findByNumberField(LongValue value);
    }
    public interface StringRepository extends JpaRepository<StringRow, StringId> { }
    public interface LongRepository extends JpaRepository<LongRow, LongId> {
        List<LongRow> findByNumberField(LongValue value);
    }
}
