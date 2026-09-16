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
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryPage;
import io.cratis.arc.queries.QueryPaging;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.queries.QuerySortDirection;
import io.cratis.arc.queries.QuerySorting;
import io.cratis.arc.results.QueryResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises real Java record properties through the default pipeline and its Java facade. */
final class QueryPagingSortingJavaConformanceTest {
    private static final List<Row> ROWS = List.of(new Row("a"), new Row("c"), new Row("b"), new Row("d"));
    private static final FullyQualifiedQueryName NAME = new FullyQualifiedQueryName("Tests.JavaPagingRows.all");

    @Test
    void recordListIsSortedBeforeZeroBasedPagingWithBothCapabilityFlagsFalse() throws Exception {
        QueryResult<?> result = perform(ROWS, SequenceKind.LIST);

        assertEquals(List.of(new Row("b"), new Row("a")), result.getData());
        assertPaging(result, 1, 2, 4L, 2);
        assertEquals(List.of("a", "c", "b", "d"), ROWS.stream().map(Row::name).toList());
    }

    @Test
    void nonCollectionRecordIterableUsesTheDefaultSortingAndPaging() throws Exception {
        Iterable<Row> iterable = ROWS::iterator;
        QueryResult<?> result = perform(iterable, SequenceKind.COLLECTION);

        assertEquals(List.of(new Row("b"), new Row("a")), result.getData());
        assertPaging(result, 1, 2, 4L, 2);
    }

    @Test
    void providerQueryPageRetainsItsRecordOrderItemsAndTotalWithoutRepaging() throws Exception {
        QueryPage<Row> page = new QueryPage<>(List.of(new Row("a"), new Row("c")), 3, 2, 40L);
        QueryResult<?> result = perform(page, SequenceKind.LIST);

        assertEquals(page.getItems(), result.getData());
        assertPaging(result, 3, 2, 40L, 20);
    }

    @Test
    void originalRecordArrayIsNormalizedButBypassesIterableSortingPagingAndCounting() throws Exception {
        QueryResult<?> result = perform(ROWS.toArray(Row[]::new), SequenceKind.ARRAY);

        assertEquals(ROWS, result.getData());
        assertPaging(result, 0, 0, 0L, 0);
    }

    private static QueryResult<?> perform(Object value, SequenceKind kind) throws Exception {
        QueryDescriptor descriptor = new QueryDescriptor(
            "all", "Tests.JavaPagingRows", TypeShapeDescriptor.sequence(kind, TypeShapeDescriptor.value(Row.class.getName())));
        BlockingQueryPerformerAdapter performer = new BlockingQueryPerformerAdapter(new BlockingQueryPerformer() {
            @Override public QueryDescriptor getDescriptor() { return descriptor; }
            @Override public FullyQualifiedQueryName getFullyQualifiedName() { return NAME; }
            // No model request/context injection or custom renderer substitutes for the pipeline-owned operations.
            @Override public Object perform(QueryContext context) { return value; }
        });
        assertFalse(descriptor.getSupportsPaging());
        assertFalse(descriptor.getSupportsSorting());
        assertFalse(performer.getSupportsPaging());
        assertFalse(performer.getSupportsSorting());
        assertTrue(descriptor.getParameters().isEmpty());
        ConcurrentQueryPerformerRegistry registry = new ConcurrentQueryPerformerRegistry();
        registry.register(performer);
        ServiceResolver services = new ServiceResolver() {
            @Override public <T> T resolve(Class<T> type) { return null; }
        };
        QueryRequest request = new QueryRequest(NAME, Map.of(), new QueryPaging(1, 2),
            new QuerySorting("name", QuerySortDirection.DESCENDING));
        try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(Runnable::run)) {
            QueryResult<?> result = scope.queries(new DefaultQueryPipeline(registry)).perform(
                request, new QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), services))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(result.isSuccess(), result.getExceptionMessages().toString());
            return result;
        }
    }

    private static void assertPaging(QueryResult<?> result, int page, int size, long total, int totalPages) {
        assertEquals(page, result.getPaging().getPage());
        assertEquals(size, result.getPaging().getSize());
        assertEquals(total, result.getPaging().getTotalItems());
        assertEquals(totalPages, result.getPaging().getTotalPages());
    }

    public record Row(String name) { }
}
