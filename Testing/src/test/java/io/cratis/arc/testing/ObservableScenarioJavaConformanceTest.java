// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing;

import io.cratis.arc.java.AsyncQueryPerformer;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.concepts.ConceptAs;
import io.cratis.arc.queries.BlockingObservableQueryEmissionGuard;
import io.cratis.arc.queries.ObservableQueryEmissionVerdict;
import io.cratis.arc.java.AsyncQueryPerformerAdapter;
import io.cratis.arc.java.BlockingQueryPerformer;
import io.cratis.arc.java.BlockingQueryPerformerAdapter;
import io.cratis.arc.java.BlockingQueryValidator;
import io.cratis.arc.java.BlockingQueryValidatorAdapter;
import io.cratis.arc.metadata.AuthorizationMetadata;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.metadata.RouteOptions;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryHttpMethodType;
import io.cratis.arc.queries.QueryRequest;
import io.cratis.arc.queries.QueryTransportType;
import io.cratis.arc.results.ValidationResult;
import io.cratis.arc.results.ValidationResultSeverity;
import io.cratis.arc.testing.java.AsyncObservableQueryScenario;
import io.cratis.arc.testing.java.ObservableQueryScenarioHandle;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Unit;
import kotlinx.coroutines.CompletableJob;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.JobKt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ObservableScenarioJavaConformanceTest {
    private static final FullyQualifiedQueryName NAME = new FullyQualifiedQueryName("Tests.Observable.values");

    @Test
    void ownedJavaScenarioPreservesIndependentConceptAndArrayArguments() throws Exception {
        Flow.Publisher<String> publisher = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean complete;
            private int next;
            @Override public void request(long count) {
                while (!complete && count-- > 0 && next < 2) subscriber.onNext(++next == 1 ? "one" : "two");
                if (!complete && next == 2) { complete = true; subscriber.onComplete(); }
            }
            @Override public void cancel() { complete = true; }
        });
        int[] numbers = {1};
        ScenarioId id = new ScenarioId("one");
        List<Integer> seen = new ArrayList<>();
        ObservableQueryScenario<String> scenario = scenario(publisher, false)
            .addEmissionGuard((BlockingObservableQueryEmissionGuard) context -> {
                ((int[]) context.getArguments().get("numbers"))[0] = 99;
                return ObservableQueryEmissionVerdict.ALLOW;
            })
            .addEmissionGuard((BlockingObservableQueryEmissionGuard) context -> {
                seen.add(((int[]) context.getArguments().get("numbers"))[0]);
                assertEquals(id, context.getArguments().get("id"));
                assertNotSame(id, context.getArguments().get("id"));
                return ObservableQueryEmissionVerdict.ALLOW;
            });
        try (JavaAsyncScope owner = JavaAsyncScope.owningExecutorService(Executors.newSingleThreadExecutor())) {
            new AsyncObservableQueryScenario<>(scenario, owner).collectAsync(2, 3000, Map.of("numbers", numbers, "id", id))
                .toCompletableFuture().get(5, TimeUnit.SECONDS).shouldSucceed().shouldHaveEmissionCount(2).shouldHaveData(1, "two");
        }
        assertEquals(List.of(1, 1), seen);
        assertEquals(1, numbers[0]);
    }

    public record ScenarioId(String value) implements ConceptAs<String> { }

    @Test
    void callbacksExposeFiniteAndEmptyCompletionWithoutPromisingTheMaximum() throws Exception {
        for (boolean empty : List.of(false, true)) {
            Flow.Publisher<String> publisher = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                private boolean complete;
                @Override public void request(long count) {
                    if (complete) return;
                    complete = true;
                    if (!empty) subscriber.onNext("value");
                    subscriber.onComplete();
                }
                @Override public void cancel() { complete = true; }
            });
            try (OwnedScope scope = new OwnedScope()) {
                ObservableQueryScenarioResult<String> result = collect(scenario(publisher, false), scope);
                result.shouldSucceed().shouldHaveEmissionCount(empty ? 0 : 1);
                if (!empty) result.shouldHaveData(0, "value");
                assertThrows(AssertionError.class, () -> result.shouldHaveEmissionCount(5));
            }
        }
    }

    @Test
    void descriptorSeverityAndExplicitNullOverrideAreUsableThroughJavaCallbacks() throws Exception {
        for (boolean strict : List.of(false, true)) {
            for (boolean override : List.of(false, true)) {
                Flow.Publisher<String> empty = subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long count) { subscriber.onComplete(); }
                    @Override public void cancel() { }
                });
                ObservableQueryScenario<String> scenario = scenario(empty, strict)
                    .addValidator(new BlockingQueryValidatorAdapter(new BlockingQueryValidator() {
                        @Override public FullyQualifiedQueryName getQueryName() { return NAME; }
                        @Override public List<ValidationResult> validate(QueryRequest request, QueryContext context) {
                            return List.of(new ValidationResult(ValidationResultSeverity.Warning, "warning"));
                        }
                    }));
                if (override) scenario.withAllowedValidationSeverity(null);
                try (OwnedScope scope = new OwnedScope()) {
                    ObservableQueryScenarioResult<String> result = collect(scenario, scope);
                    if (strict && !override) {
                        assertEquals(ValidationResultSeverity.Warning,
                            result.shouldFail().getValidationResults().get(0).getSeverity());
                    } else {
                        result.shouldSucceed();
                    }
                    result.shouldHaveEmissionCount(0);
                }
            }
        }
    }

    @Test
    void legacyCallbackBridgeSilentlyCancelsOnInternalOpeningTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Object> opening = new CompletableFuture<>();
        ObservableQueryScenario<String> scenario = new ObservableQueryScenario<>(new AsyncQueryPerformerAdapter(
            new AsyncQueryPerformer() {
                @Override public QueryDescriptor getDescriptor() { return descriptor(false); }
                @Override public FullyQualifiedQueryName getFullyQualifiedName() { return NAME; }
                @Override public CompletionStage<?> perform(QueryContext context) {
                    entered.countDown();
                    return opening;
                }
            }));
        try (OwnedScope scope = new OwnedScope()) {
            AtomicBoolean callback = new AtomicBoolean();
            new AsyncObservableQueryScenario<>(scenario, scope.scope).collect(
                1, 500, result -> callback.set(true), failure -> callback.set(true));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            scope.awaitChildren();
            assertTrue(opening.isCancelled(), "Scenario timeout must cancel suspended opening");
            assertFalse(callback.get(), "Characterization: the legacy bridge rethrows timeout as cancellation");
        }
    }

    @Test
    void legacyCallbackBridgeSilentlyCancelsOnInternalCollectionTimeout() throws Exception {
        WaitingPublisher publisher = new WaitingPublisher();
        try (OwnedScope scope = new OwnedScope()) {
            AtomicBoolean callback = new AtomicBoolean();
            new AsyncObservableQueryScenario<>(scenario(publisher, false), scope.scope).collect(
                1, 500, result -> callback.set(true), failure -> callback.set(true));
            assertTrue(publisher.entered.await(2, TimeUnit.SECONDS));
            scope.awaitChildren();
            assertTrue(publisher.cancelled.get());
            assertFalse(callback.get(), "Characterization: internal timeout invokes neither callback");
        }
    }

    @Test
    void handleCancellationCleansPublisherWithoutInvokingCallbacks() throws Exception {
        WaitingPublisher publisher = new WaitingPublisher();
        try (OwnedScope scope = new OwnedScope()) {
            AtomicBoolean callback = new AtomicBoolean();
            ObservableQueryScenarioHandle handle = new AsyncObservableQueryScenario<>(
                scenario(publisher, false), scope.scope).collect(
                    1, 10_000, result -> callback.set(true), failure -> callback.set(true));
            assertTrue(publisher.entered.await(2, TimeUnit.SECONDS));
            handle.cancel();
            scope.awaitChildren();
            assertTrue(publisher.cancelled.get());
            assertFalse(callback.get());
        }
    }

    private static ObservableQueryScenario<String> scenario(Flow.Publisher<String> publisher, boolean strict) {
        return new ObservableQueryScenario<>(new BlockingQueryPerformerAdapter(new BlockingQueryPerformer() {
            @Override public QueryDescriptor getDescriptor() { return descriptor(strict); }
            @Override public FullyQualifiedQueryName getFullyQualifiedName() { return NAME; }
            @Override public Object perform(QueryContext context) { return publisher; }
        }));
    }

    private static QueryDescriptor descriptor(boolean strict) {
        return new QueryDescriptor("values", "Tests.Observable", "java.lang.String", List.of(),
            new RouteOptions(), NAME.getValue(), List.of("Tests"), new AuthorizationMetadata(true), null,
            QueryHttpMethodType.AUTO, QueryTransportType.OBSERVABLE, false, false, strict);
    }

    private static ObservableQueryScenarioResult<String> collect(
        ObservableQueryScenario<String> scenario, OwnedScope scope) throws Exception {
        CompletableFuture<ObservableQueryScenarioResult<String>> result = new CompletableFuture<>();
        new AsyncObservableQueryScenario<>(scenario, scope.scope).collect(
            5, result::complete, result::completeExceptionally);
        return result.get(5, TimeUnit.SECONDS);
    }

    private static final class WaitingPublisher implements Flow.Publisher<String> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        @Override public void subscribe(Flow.Subscriber<? super String> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) { entered.countDown(); }
                @Override public void cancel() { cancelled.set(true); }
            });
        }
    }

    private static final class OwnedScope implements AutoCloseable {
        private final CompletableJob owner = JobKt.Job(null);
        private final CoroutineScope scope = CoroutineScopeKt.CoroutineScope(owner.plus(Dispatchers.getDefault()));

        private void awaitChildren() throws Exception {
            for (java.util.Iterator<Job> children = owner.getChildren().iterator(); children.hasNext();) {
                CompletableFuture<Void> completed = new CompletableFuture<>();
                children.next().invokeOnCompletion(cause -> { completed.complete(null); return Unit.INSTANCE; });
                completed.get(3, TimeUnit.SECONDS);
            }
        }

        @Override public void close() {
            owner.cancel(null);
            try {
                awaitChildren();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while joining scenario children", exception);
            } catch (Exception exception) {
                throw new AssertionError("Scenario children did not finish", exception);
            }
        }
    }
}
