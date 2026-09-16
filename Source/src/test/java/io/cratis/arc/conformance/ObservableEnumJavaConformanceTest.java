// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.authorization.ArcPrincipal;
import io.cratis.arc.commands.ServiceResolver;
import io.cratis.arc.concepts.ArcEnum;
import io.cratis.arc.java.AsyncObservableQueryOpenResult;
import io.cratis.arc.java.BlockingQueryPerformer;
import io.cratis.arc.java.BlockingQueryPerformerAdapter;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.json.ArcObjectMapper;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.metadata.RouteOptions;
import io.cratis.arc.queries.BlockingObservableQueryEmissionGuard;
import io.cratis.arc.queries.ChangeSetComputer;
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry;
import io.cratis.arc.queries.DefaultObservableQueryEmissionGuards;
import io.cratis.arc.queries.DefaultObservableQueryPipeline;
import io.cratis.arc.queries.DefaultQueryRenderers;
import io.cratis.arc.queries.DefaultReadModelInterceptors;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryExecutionOptions;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.queries.QueryTransportType;
import io.cratis.arc.queries.ObservableQueryEmissionVerdict;
import io.cratis.arc.results.QueryResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ObservableEnumJavaConformanceTest {
    @Test
    void ordinaryJavaEnumsReachGuardsThroughTheOwnedAsyncFacade() throws Exception {
        assertGuardedQuery(Plain.FIRST, new Plain[] {Plain.FIRST}, true);
    }

    @Test
    void arcEnumWireValuesReachGuardsWithoutLosingConstantIdentity() throws Exception {
        assertEquals("23", ArcObjectMapper.create().writeValueAsString(Wire.FIRST));
        assertSame(Wire.FIRST, ArcObjectMapper.create().readValue("23", Wire.class));
        assertGuardedQuery(Wire.FIRST, new Wire[] {Wire.FIRST}, true);
        assertEquals(23, Wire.FIRST.value());
    }

    @Test
    void constantSubclassFinalScalarFieldsAreCheckedAlongWithTheDeclaringEnum() throws Exception {
        assertGuardedQuery(ScalarState.FIRST, new ScalarState[] {ScalarState.FIRST}, true);
        assertEquals("first", ScalarState.FIRST.label());
    }

    @Test
    void mutableFieldsAndFinalMutableReferencesDenyBeforeJavaGuards() throws Exception {
        assertGuardedQuery(MutableField.VALUE, new MutableField[] {MutableField.VALUE}, false);
        assertGuardedQuery(MutableReference.VALUE, new MutableReference[] {MutableReference.VALUE}, false);
        assertEquals(0, MutableField.VALUE.state);
        assertEquals(List.of("original"), MutableReference.VALUE.state);
    }

    private static void assertGuardedQuery(Enum<?> value, Object array, boolean allowed) throws Exception {
        for (boolean configuredMapper : List.of(false, true)) {
            FullyQualifiedQueryName name = new FullyQualifiedQueryName("JavaEnums.observe");
            AtomicInteger calls = new AtomicInteger();
            BlockingObservableQueryEmissionGuard guard = context -> {
                assertSame(value, context.getArguments().get("value"));
                Object copiedArray = context.getArguments().get("array");
                assertEquals(array.getClass(), copiedArray.getClass());
                assertNotSame(array, copiedArray);
                assertSame(value, java.lang.reflect.Array.get(copiedArray, 0));
                java.lang.reflect.Array.set(copiedArray, 0, null);
                calls.incrementAndGet();
                return ObservableQueryEmissionVerdict.ALLOW;
            };
            DefaultObservableQueryEmissionGuards guards = configuredMapper
                ? new DefaultObservableQueryEmissionGuards(List.of(guard, guard), ArcObjectMapper.create())
                : new DefaultObservableQueryEmissionGuards(List.of(guard, guard));
            ConcurrentQueryPerformerRegistry performers = new ConcurrentQueryPerformerRegistry();
            performers.register(new BlockingQueryPerformerAdapter(new BlockingQueryPerformer() {
                @Override public FullyQualifiedQueryName getFullyQualifiedName() { return name; }
                @Override public QueryDescriptor getDescriptor() {
                    return new QueryDescriptor("observe", "JavaEnums", String.class.getName(), List.of(),
                        new RouteOptions(null, QueryTransportType.OBSERVABLE));
                }
                @Override public Object perform(QueryContext context) {
                    assertSame(value, context.getRequest().getArguments().get("value"));
                    Flow.Publisher<String> source = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                        private boolean sent;
                        @Override public void request(long count) {
                            if (!sent && count > 0) {
                                sent = true;
                                subscriber.onNext("result");
                                subscriber.onComplete();
                            }
                        }
                        @Override public void cancel() { sent = true; }
                    });
                    return source;
                }
            }));
            ServiceResolver services = new ServiceResolver() {
                @Override public <T> T resolve(Class<T> type) { return null; }
            };
            DefaultObservableQueryPipeline core = new DefaultObservableQueryPipeline(performers, List.of(),
                new ChangeSetComputer(), new DefaultQueryRenderers(), new DefaultReadModelInterceptors(), guards);
            try (JavaAsyncScope scope = JavaAsyncScope.owningExecutorService(Executors.newSingleThreadExecutor())) {
                AsyncObservableQueryOpenResult.Stream stream = (AsyncObservableQueryOpenResult.Stream) scope.observableQueries(core)
                    .open(new QueryRequest(name, Map.of("value", value, "array", array)),
                        new QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), services))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
                CompletableFuture<QueryResult<?>> result = new CompletableFuture<>();
                stream.getResults().subscribe(new Flow.Subscriber<>() {
                    @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(1); }
                    @Override public void onNext(QueryResult<?> item) { result.complete(item); }
                    @Override public void onError(Throwable error) { result.completeExceptionally(error); }
                    @Override public void onComplete() { }
                });
                QueryResult<?> observed = result.get(5, TimeUnit.SECONDS);
                assertEquals(allowed, observed.isSuccess());
                if (allowed) assertEquals("result", observed.getData());
                else assertFalse(observed.isAuthorized());
                assertEquals(allowed ? 2 : 0, calls.get());
                assertSame(value, java.lang.reflect.Array.get(array, 0));
            }
        }
    }

    private enum Plain { FIRST }
    public enum Wire implements ArcEnum {
        FIRST(23);
        private final int wire;
        Wire(int wire) { this.wire = wire; }
        @Override public int value() { return wire; }
    }
    private enum ScalarState {
        FIRST("base") {
            private final String text = "first";
            @Override String label() { return text; }
        };
        private final String base;
        ScalarState(String base) { this.base = base; }
        abstract String label();
    }
    private enum MutableField { VALUE; private int state; }
    private enum MutableReference { VALUE; private final List<String> state = new java.util.ArrayList<>(List.of("original")); }
}
