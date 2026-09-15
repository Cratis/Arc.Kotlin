// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.java.BlockingQueryPerformer;
import io.cratis.arc.java.BlockingQueryPerformerAdapter;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.metadata.SequenceKind;
import io.cratis.arc.metadata.TypeShapeDescriptor;
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
import io.cratis.arc.queries.DefaultQueryPipeline;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.PublicSortBase;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.queries.QueryPaging;
import io.cratis.arc.queries.QuerySorting;
import io.cratis.arc.queries.QuerySortDirection;
import io.cratis.arc.results.QueryResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class QueryablePropertyAccessJavaConformanceTest {
    @ParameterizedTest
    @ValueSource(strings = {"privateRank", "protectedRank", "packageRank", "staticRank", "missing"})
    void hiddenOrStaticFieldsDoNotInfluenceReturnedRowOrder(String field) throws Exception {
        QueryResult<?> result = perform(List.of(new ConfidentialRow("first", 99), new ConfidentialRow("second", 1)), field);
        assertFalse(result.isSuccess(), field);
        assertNull(result.getData());
    }

    @Test
    void privateFieldFailsEvenWhenThereIsOnlyOneRow() throws Exception {
        assertFalse(perform(List.of(new ConfidentialRow("single", 1)), "privateRank").isSuccess());
    }

    @Test
    void publicFieldsOnNonpublicJavaClassesRemainSortable() throws Exception {
        ConfidentialRow first = new ConfidentialRow("a", 99);
        ConfidentialRow second = new ConfidentialRow("b", 1);
        QueryResult<?> result = perform(List.of(second, first), "label");
        assertTrue(result.isSuccess(), result.getExceptionMessages().toString());
        assertEquals(List.of(first, second), result.getData());
    }

    @Test
    void publicBeanGetterControlsThePropertyRatherThanItsPrivateBackingField() throws Exception {
        BeanRow first = new BeanRow("ab"); // getter returns ba
        BeanRow second = new BeanRow("ba"); // getter returns ab
        QueryResult<?> result = perform(List.of(first, second), "name");
        assertTrue(result.isSuccess(), result.getExceptionMessages().toString());
        assertEquals(List.of(second, first), result.getData());
    }

    @Test
    void recordComponentsUseTheirPublicAccessor() throws Exception {
        RecordRow first = new RecordRow("ab");
        RecordRow second = new RecordRow("ba");
        QueryResult<?> result = perform(List.of(first, second), "name");
        assertTrue(result.isSuccess(), result.getExceptionMessages().toString());
        assertEquals(List.of(second, first), result.getData());
    }

    @Test
    void booleanBeanAccessorIsAnInstanceProperty() throws Exception {
        BooleanRow first = new BooleanRow(false);
        BooleanRow second = new BooleanRow(true);
        QueryResult<?> result = perform(List.of(second, first), "active");
        assertTrue(result.isSuccess(), result.getExceptionMessages().toString());
        assertEquals(List.of(first, second), result.getData());
    }

    @Test
    void inheritedKotlinGetterKeepsItsVisibilityAndJvmName() throws Exception {
        MixedRow first = new MixedRow("a");
        MixedRow second = new MixedRow("b");
        assertEquals(List.of(first, second), perform(List.of(second, first), "label").getData());
        assertFalse(perform(List.of(second, first), "internalRank").isSuccess());
    }

    @Test
    void javaBeanAliasCannotReenterAKotlinInternalGetter() throws Exception {
        QueryResult<?> result = perform(List.of(new GetterAliasRow()), "InternalRank");
        assertFalse(result.isSuccess());
        assertNull(result.getData());
    }

    @Test
    void publicKotlinFunctionCanServeAFieldBackedJavaBeanGetter() throws Exception {
        GetterAliasRow first = new GetterAliasRow("ab");
        GetterAliasRow second = new GetterAliasRow("ba");
        QueryResult<?> result = perform(List.of(first, second), "sortingLabel");
        assertTrue(result.isSuccess(), result.getExceptionMessages().toString());
        assertEquals(List.of(second, first), result.getData());
    }

    @Test
    void wrappedGetterCancellationCancelsTheJavaStage() {
        ThrowingBean row = new ThrowingBean(false);
        assertThrows(CancellationException.class, () -> perform(List.of(row, row), "label"));
    }

    @Test
    void getterFatalErrorRemainsAnExceptionalJavaStage() {
        ThrowingBean row = new ThrowingBean(true);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> perform(List.of(row, row), "label"));
        assertTrue(failure.getCause() instanceof AssertionError, failure.toString());
    }

    @Test
    void getterOnlyMethodsAreNotNewlyExposedAsFieldBackedProperties() throws Exception {
        GetterOnlyRow row = new GetterOnlyRow();
        QueryResult<?> result = perform(List.of(row, row), "label");
        assertFalse(result.isSuccess());
        assertNull(result.getData());
        assertEquals(0, row.reads);
    }

    private static QueryResult<?> perform(List<?> rows, String field) throws Exception {
        FullyQualifiedQueryName name = new FullyQualifiedQueryName("Access.javaRows");
        ConcurrentQueryPerformerRegistry registry = new ConcurrentQueryPerformerRegistry();
        registry.register(new BlockingQueryPerformerAdapter(new BlockingQueryPerformer() {
            @Override public FullyQualifiedQueryName getFullyQualifiedName() { return name; }
            @Override public QueryDescriptor getDescriptor() {
                return new QueryDescriptor("javaRows", "Access", TypeShapeDescriptor.sequence(
                    SequenceKind.LIST, TypeShapeDescriptor.value("Row")));
            }
            @Override public Object perform(QueryContext context) { return rows; }
        }));
        ServiceResolver services = new ServiceResolver() {
            @Override public <T> T resolve(Class<T> type) { return null; }
        };
        try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(Runnable::run)) {
            return scope.queries(new DefaultQueryPipeline(registry)).perform(
                new QueryRequest(name, Map.of(), QueryPaging.UNPAGED, new QuerySorting(field, QuerySortDirection.ASCENDING)),
                new QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), services))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static final class ConfidentialRow {
        public final String label;
        private final int privateRank;
        protected final int protectedRank;
        final int packageRank;
        public static final int staticRank = 1;
        private ConfidentialRow(String label, int rank) {
            this.label = label;
            privateRank = rank;
            protectedRank = rank;
            packageRank = rank;
        }
    }

    private static final class BeanRow {
        private final String name;
        private BeanRow(String name) { this.name = name; }
        public String getName() { return new StringBuilder(name).reverse().toString(); }
    }

    private record RecordRow(String name) {
        @Override public String name() { return new StringBuilder(name).reverse().toString(); }
    }

    private static final class GetterOnlyRow {
        private int reads;
        public String getLabel() { reads++; return "not a field-backed property"; }
    }

    private static final class ThrowingBean {
        private final String label = "not a getter substitute";
        private final boolean fatal;
        private ThrowingBean(boolean fatal) { this.fatal = fatal; }
        public String getLabel() {
            if (fatal) throw new AssertionError("fatal getter");
            throw new CompletionException(new CancellationException("stop sorting"));
        }
    }

    private static final class GetterAliasRow extends PublicSortBase {
        private final int InternalRank = 0;
        private final String sortingLabel = "not a getter substitute";
        private GetterAliasRow() { this("alias"); }
        private GetterAliasRow(String label) { super(label); }
    }

    private static final class MixedRow extends PublicSortBase {
        private MixedRow(String label) { super(label); }
    }

    private static final class BooleanRow {
        private final boolean active;
        private BooleanRow(boolean active) { this.active = active; }
        public boolean isActive() { return active; }
    }
}
