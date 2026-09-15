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
import io.cratis.arc.queries.BlockingQueryRendererFor;
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
import io.cratis.arc.queries.DefaultQueryPipeline;
import io.cratis.arc.queries.DefaultQueryRenderers;
import io.cratis.arc.queries.DefaultReadModelInterceptors;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryPaging;
import io.cratis.arc.queries.QueryRendererResult;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.queries.QuerySorting;
import io.cratis.arc.queries.QuerySortDirection;
import io.cratis.arc.queries.QueryableQueryRenderer;
import io.cratis.arc.results.PagingInfo;
import io.cratis.arc.results.QueryResult;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QueryRendererOwnershipJavaConformanceTest {
    private static final FullyQualifiedQueryName NAME = new FullyQualifiedQueryName("Composition.javaRows");

    @Test
    void applicationFilteringIsNotReplacedWithOriginalRows() throws Exception {
        Rows rows = new Rows(false);
        BlockingQueryRendererFor<Rows> filter = new BlockingQueryRendererFor<>() {
            @Override public Class<Rows> queryType() { return Rows.class; }
            @Override public QueryRendererResult renderBlocking(Rows query, QueryRendererResult current, QueryContext context) {
                return new QueryRendererResult(List.of(), current.getPaging());
            }
        };
        QueryResult<?> result = perform(rows, new DefaultQueryRenderers(List.of(filter)));
        assertEquals(List.of(), result.getData());
        assertEquals(0, rows.enumerations);
    }

    @Test
    void aLateProviderRendererOwnsPagingWithoutPrematureEnumeration() throws Exception {
        Rows rows = new Rows(true);
        BlockingQueryRendererFor<Rows> provider = new BlockingQueryRendererFor<>() {
            @Override public Class<Rows> queryType() { return Rows.class; }
            @Override public int order() { return 100; }
            @Override public QueryRendererResult renderBlocking(Rows query, QueryRendererResult current, QueryContext context) {
                return new QueryRendererResult(List.of(new Row(3, true)), new PagingInfo(2, 1, 40));
            }
        };
        QueryResult<?> result = perform(rows, new DefaultQueryRenderers(List.of(provider)));
        assertEquals(List.of(new Row(3, true)), result.getData());
        assertEquals(2, result.getPaging().getPage());
        assertEquals(1, result.getPaging().getSize());
        assertEquals(40L, result.getPaging().getTotalItems());
        assertEquals(0, rows.enumerations);
    }

    @Test
    void explicitIterableStageSortsTheFilteredCurrentData() throws Exception {
        Rows rows = new Rows(false);
        BlockingQueryRendererFor<Rows> filter = new BlockingQueryRendererFor<>() {
            @Override public Class<Rows> queryType() { return Rows.class; }
            @Override public QueryRendererResult renderBlocking(Rows query, QueryRendererResult current, QueryContext context) {
                List<Row> visible = StreamSupport.stream(((Iterable<?>) current.getData()).spliterator(), false)
                    .map(Row.class::cast).filter(Row::visible).toList();
                return new QueryRendererResult(visible, current.getPaging());
            }
        };
        QueryResult<?> result = perform(rows, new DefaultQueryRenderers(List.of(filter, new QueryableQueryRenderer())));
        assertEquals(List.of(new Row(1, true)), result.getData());
        assertEquals(2L, result.getPaging().getTotalItems());
        assertEquals(1, rows.enumerations);
    }

    private static QueryResult<?> perform(Rows rows, DefaultQueryRenderers renderers) throws Exception {
        ConcurrentQueryPerformerRegistry registry = new ConcurrentQueryPerformerRegistry();
        registry.register(new BlockingQueryPerformerAdapter(new BlockingQueryPerformer() {
            @Override public FullyQualifiedQueryName getFullyQualifiedName() { return NAME; }
            @Override public QueryDescriptor getDescriptor() {
                return new QueryDescriptor("javaRows", "Composition", TypeShapeDescriptor.sequence(
                    SequenceKind.LIST, TypeShapeDescriptor.value(Row.class.getName())));
            }
            @Override public Object perform(QueryContext context) { return rows; }
        }));
        DefaultQueryPipeline pipeline = new DefaultQueryPipeline(registry, List.of(), renderers, new DefaultReadModelInterceptors());
        ServiceResolver services = new ServiceResolver() {
            @Override public <T> T resolve(Class<T> type) { return null; }
        };
        QueryRequest request = new QueryRequest(NAME, Map.of(), new QueryPaging(0, 1),
            new QuerySorting("id", QuerySortDirection.ASCENDING));
        try (JavaAsyncScope scope = JavaAsyncScope.usingExecutor(Runnable::run)) {
            QueryResult<?> result = scope.queries(pipeline).perform(request,
                new QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), services))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(result.isSuccess(), result.getExceptionMessages().toString());
            return result;
        }
    }

    private static final class Rows implements Iterable<Row> {
        private final boolean failOnEnumeration;
        private int enumerations;
        private Rows(boolean failOnEnumeration) { this.failOnEnumeration = failOnEnumeration; }
        @Override public Iterator<Row> iterator() {
            enumerations++;
            if (failOnEnumeration) throw new IllegalStateException("Provider query enumerated by fallback");
            return List.of(new Row(0, false), new Row(2, true), new Row(1, true)).iterator();
        }
    }

    public record Row(int id, boolean visible) { }
}
