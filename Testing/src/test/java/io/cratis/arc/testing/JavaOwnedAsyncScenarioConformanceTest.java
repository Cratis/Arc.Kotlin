// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing;

import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.java.AsyncCommandHandler;
import io.cratis.arc.java.AsyncCommandHandlerAdapter;
import io.cratis.arc.metadata.CommandDescriptor;
import io.cratis.arc.java.AsyncQueryPerformer;
import io.cratis.arc.java.AsyncQueryPerformerAdapter;
import io.cratis.arc.java.JavaAsyncScope;
import io.cratis.arc.metadata.AuthorizationMetadata;
import io.cratis.arc.metadata.QueryDescriptor;
import io.cratis.arc.metadata.RouteOptions;
import io.cratis.arc.queries.FullyQualifiedQueryName;
import io.cratis.arc.queries.ObservableQueryTransferMode;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.queries.QueryHttpMethodType;
import io.cratis.arc.queries.QueryPaging;
import io.cratis.arc.queries.QuerySortDirection;
import io.cratis.arc.queries.QuerySorting;
import io.cratis.arc.queries.QueryTransportType;
import io.cratis.arc.testing.java.AsyncCommandScenario;
import io.cratis.arc.testing.java.AsyncObservableQueryScenario;
import io.cratis.arc.testing.java.AsyncQueryScenario;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** New Java usage deliberately has no Kotlin or coroutine imports. */
final class JavaOwnedAsyncScenarioConformanceTest {
    private static final FullyQualifiedQueryName NAME = new FullyQualifiedQueryName("Tests.Async.values");

    @Test
    void everyOwnerConstructorAndShortQueryOverloadExecutes() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope owner = JavaAsyncScope.owningExecutorService(executor)) {
            ManualCommandHandler handler = new ManualCommandHandler();
            ManualQueryPerformer performer = new ManualQueryPerformer();
            ManualArtifactModule module = new ManualArtifactModule(handler, performer);
            List<AsyncCommandScenario<TestCommand>> commands = List.of(
                new AsyncCommandScenario<>(new CommandScenario<TestCommand>(handler), owner),
                new AsyncCommandScenario<>(handler, owner),
                new AsyncCommandScenario<>(module, TestCommand.class, owner));
            for (AsyncCommandScenario<TestCommand> command : commands) {
                command.validate(new TestCommand("validate")).toCompletableFuture().get(3, TimeUnit.SECONDS).shouldSucceed();
                assertEquals("value", command.execute(new TestCommand("value")).toCompletableFuture()
                    .get(3, TimeUnit.SECONDS).shouldSucceed().shouldHaveResponse(TestResponse.class).getValue());
            }
            assertEquals(3, handler.getInvocationCount());
            List<AsyncQueryScenario<TestModel>> queries = List.of(
                new AsyncQueryScenario<>(new QueryScenario<TestModel>(performer), owner),
                new AsyncQueryScenario<>(performer, owner),
                new AsyncQueryScenario<>(module, ManualQueryPerformer.QUERY_NAME, owner));
            for (AsyncQueryScenario<TestModel> query : queries) {
                query.perform().toCompletableFuture().get(3, TimeUnit.SECONDS).shouldHaveData(new TestModel("default"));
                query.perform(Map.of()).toCompletableFuture().get(3, TimeUnit.SECONDS).shouldSucceed();
                query.perform(Map.of(), new QueryPaging(0, 0)).toCompletableFuture().get(3, TimeUnit.SECONDS).shouldSucceed();
                query.perform(Map.of(), new QueryPaging(0, 0), new QuerySorting("", QuerySortDirection.ASCENDING))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS).shouldSucceed();
            }
        } finally { executor.shutdownNow(); }
    }

    @Test
    void everyCollectAsyncOverloadReturnsFiniteData() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope owner = JavaAsyncScope.owningExecutorService(executor)) {
            AsyncObservableQueryScenario<String> scenario = observable(owner, completedPublisher(), null);
            QueryPaging paging = new QueryPaging(0, 0);
            QuerySorting sorting = new QuerySorting("", QuerySortDirection.ASCENDING);
            List<CompletionStage<ObservableQueryScenarioResult<String>>> stages = List.of(
                scenario.collectAsync(5), scenario.collectAsync(5, 3000), scenario.collectAsync(5, 3000, Map.of()),
                scenario.collectAsync(5, 3000, Map.of(), paging), scenario.collectAsync(5, 3000, Map.of(), paging, sorting),
                scenario.collectAsync(5, 3000, Map.of(), paging, sorting, ObservableQueryTransferMode.FULL));
            for (CompletionStage<ObservableQueryScenarioResult<String>> stage : stages) {
                stage.toCompletableFuture().get(3, TimeUnit.SECONDS).shouldSucceed().shouldHaveEmissionCount(1).shouldHaveData(0, "value");
            }
        } finally { executor.shutdownNow(); }
    }

    @Test
    void pendingOpeningReturnsPromptlyUsesExecutorAndChildCancellationLeavesSiblingAlive() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Thread caller = Thread.currentThread();
        try (JavaAsyncScope owner = JavaAsyncScope.owningExecutorService(executor)) {
            CompletableFuture<Object> pending = new CompletableFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            AtomicReference<Thread> worker = new AtomicReference<>();
            AsyncQueryScenario<String> query = new AsyncQueryScenario<>(performer(false, context -> {
                worker.set(Thread.currentThread()); entered.countDown(); return pending;
            }), owner);
            CompletableFuture<QueryScenarioResult<String>> stage = assertTimeoutPreemptively(
                Duration.ofSeconds(3), () -> { return query.perform(); }).toCompletableFuture();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertFalse(stage.isDone());
            assertNotSame(caller, worker.get());
            assertSame(executor.submit(Thread::currentThread).get(3, TimeUnit.SECONDS), worker.get());
            WaitingPublisher sibling = new WaitingPublisher();
            CompletableFuture<ObservableQueryScenarioResult<String>> siblingStage = assertTimeoutPreemptively(
                Duration.ofSeconds(3), () -> { return observable(owner, sibling, null).collectAsync(1, 10_000); })
                .toCompletableFuture();
            assertTrue(sibling.entered.await(3, TimeUnit.SECONDS));
            assertFalse(siblingStage.isDone());
            stage.cancel(false);
            assertCancelled(stage);
            assertCancelled(pending);
            assertFalse(siblingStage.isDone());
            assertEquals("still alive", executor.submit(() -> "still alive").get(3, TimeUnit.SECONDS));
            siblingStage.cancel(false);
            assertTrue(sibling.cancelled.await(3, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void ownerCloseCancelsCoreAndScenarioWorkButNeverClosesBorrowedExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        JavaAsyncScope owner = JavaAsyncScope.usingExecutor(executor);
        try {
            WaitingPublisher publisher = new WaitingPublisher();
            CompletableFuture<ObservableQueryScenarioResult<String>> stage = observable(owner, publisher, null)
                .collectAsync(1, 10_000).toCompletableFuture();
            assertTrue(publisher.entered.await(3, TimeUnit.SECONDS));
            CompletableFuture<io.cratis.arc.authentication.AuthenticationResult> authentication = new CompletableFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            io.cratis.arc.authentication.DefaultAuthentication core = new io.cratis.arc.authentication.DefaultAuthentication(List.of(
                new io.cratis.arc.authentication.AsyncAuthenticationHandlerAdapter(context -> { entered.countDown(); return authentication; })));
            CompletableFuture<?> coreStage = owner.authentication(core)
                .handleAuthentication(new io.cratis.arc.authentication.AuthenticationRequestContext()).toCompletableFuture();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            owner.close();
            assertCancelled(stage); assertCancelled(coreStage); assertCancelled(authentication);
            assertTrue(publisher.cancelled.await(3, TimeUnit.SECONDS));
            assertFalse(executor.isShutdown());
            assertEquals("borrowed", executor.submit(() -> "borrowed").get(3, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> owner.authentication(core));
        } finally { owner.close(); executor.shutdownNow(); }
    }

    @Test
    void transferredExecutorClosesAndLateScenarioCallsNeverExecuteUsersOrCallbacks() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        JavaAsyncScope owner = JavaAsyncScope.owningExecutorService(executor);
        ManualCommandHandler handler = new ManualCommandHandler();
        ManualQueryPerformer performer = new ManualQueryPerformer();
        AtomicBoolean invoked = new AtomicBoolean();
        AsyncCommandScenario<TestCommand> commands = new AsyncCommandScenario<>(handler, owner);
        AsyncQueryScenario<TestModel> queries = new AsyncQueryScenario<>(performer, owner);
        AsyncObservableQueryScenario<String> observable = observable(owner, completedPublisher(), invoked);
        try {
            owner.close();
            assertTrue(executor.isShutdown());
            assertCancelled(commands.execute(new TestCommand("late")).toCompletableFuture());
            assertCancelled(commands.validate(new TestCommand("late")).toCompletableFuture());
            assertCancelled(queries.perform().toCompletableFuture());
            assertCancelled(observable.collectAsync(1).toCompletableFuture());
            AtomicBoolean callback = new AtomicBoolean();
            observable.collect(1, result -> callback.set(true), failure -> callback.set(true)).cancel();
            // Construction borrows even a closed owner; only Core facade factories reject it synchronously.
            assertCancelled(new AsyncQueryScenario<TestModel>(performer, owner).perform().toCompletableFuture());
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0, handler.getInvocationCount()); assertEquals(0, performer.getInvocationCount());
            assertFalse(invoked.get()); assertFalse(callback.get());
        } finally { owner.close(); executor.shutdownNow(); }
    }

    @Test
    void openingAndCollectionTimeoutsAreCancelledStagesWithUpstreamCleanup() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope owner = JavaAsyncScope.owningExecutorService(executor)) {
            CompletableFuture<Object> opening = new CompletableFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            AsyncObservableQueryScenario<String> scenario = new AsyncObservableQueryScenario<>(new ObservableQueryScenario<>(
                performer(true, context -> { entered.countDown(); return opening; })), owner);
            CompletableFuture<?> stage = scenario.collectAsync(1, 500).toCompletableFuture();
            assertTrue(entered.await(3, TimeUnit.SECONDS)); assertCancelled(stage); assertCancelled(opening);
            WaitingPublisher publisher = new WaitingPublisher();
            CompletableFuture<?> collection = observable(owner, publisher, null).collectAsync(1, 500).toCompletableFuture();
            assertTrue(publisher.entered.await(3, TimeUnit.SECONDS)); assertCancelled(collection);
            assertTrue(publisher.cancelled.await(3, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void callbacksRemainHandleBasedAndSuccessExceptionReachesFailure() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope owner = JavaAsyncScope.owningExecutorService(executor)) {
            RuntimeException error = new IllegalStateException("callback");
            CompletableFuture<Throwable> failed = new CompletableFuture<>();
            observable(owner, completedPublisher(), null).collect(1, result -> {
                result.shouldHaveEmissionCount(1); throw error;
            }, failed::complete);
            assertSame(error, failed.get(3, TimeUnit.SECONDS));
            WaitingPublisher publisher = new WaitingPublisher();
            AtomicBoolean callback = new AtomicBoolean();
            var handle = observable(owner, publisher, null).collect(1, 10_000,
                result -> callback.set(true), failure -> callback.set(true));
            assertTrue(publisher.entered.await(3, TimeUnit.SECONDS)); handle.cancel();
            assertTrue(publisher.cancelled.await(3, TimeUnit.SECONDS));
            executor.submit(() -> { }).get(3, TimeUnit.SECONDS);
            assertFalse(callback.get());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void failureCallbackExceptionEscapesOwnerChildWhileSupervisorOwnerRemainsUsable() throws Exception {
        CompletableFuture<Throwable> escaped = new CompletableFuture<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(operation -> {
            Thread thread = new Thread(operation, "scenario-callback-test");
            thread.setUncaughtExceptionHandler((failedThread, failure) -> escaped.complete(failure));
            return thread;
        });
        try (JavaAsyncScope owner = JavaAsyncScope.owningExecutorService(executor)) {
            RuntimeException failure = new IllegalStateException("failure callback");
            observable(owner, completedPublisher(), null).collect(0, result -> {
                throw new AssertionError("Invalid maximum must not succeed");
            }, error -> {
                assertTrue(error instanceof IllegalArgumentException);
                throw failure;
            });
            assertSame(failure, escaped.get(3, TimeUnit.SECONDS));
            observable(owner, completedPublisher(), null).collectAsync(1).toCompletableFuture()
                .get(3, TimeUnit.SECONDS).shouldSucceed().shouldHaveEmissionCount(1);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void directExecutorMayRunInlineWithoutAnOffThreadPromise() throws Exception {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> worker = new AtomicReference<>();
        try (JavaAsyncScope owner = JavaAsyncScope.usingExecutor(Runnable::run)) {
            new AsyncQueryScenario<String>(performer(false, context -> {
                worker.set(Thread.currentThread()); return CompletableFuture.completedFuture("inline");
            }), owner).perform().toCompletableFuture().get(3, TimeUnit.SECONDS).shouldHaveData("inline");
            assertSame(caller, worker.get());
        }
    }

    @Test
    void pendingJavaCommandReturnsPromptlyAndCancellationDoesNotPoisonOwner() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope owner = JavaAsyncScope.owningExecutorService(executor)) {
            CompletableFuture<Object> upstream = new CompletableFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            AsyncCommandScenario<TestCommand> commands = new AsyncCommandScenario<>(new AsyncCommandHandlerAdapter(
                new AsyncCommandHandler() {
                    @Override public Class<?> getCommandType() { return TestCommand.class; }
                    @Override public CommandDescriptor getMetadata() { return new ManualCommandHandler().getMetadata(); }
                    @Override public CompletionStage<?> invoke(CommandContext context) {
                        entered.countDown(); return upstream;
                    }
                }), owner);
            CompletableFuture<?> stage = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> { return commands.execute(new TestCommand("pending")); }).toCompletableFuture();
            assertTrue(entered.await(3, TimeUnit.SECONDS)); assertFalse(stage.isDone());
            stage.cancel(false); assertCancelled(stage); assertCancelled(upstream);
            commands.validate(new TestCommand("still usable")).toCompletableFuture().get(3, TimeUnit.SECONDS).shouldSucceed();
        } finally { executor.shutdownNow(); }
    }

    @Test
    void observableStagesExposeOrdinaryFailureAndCallbacksStillSilenceTimeout() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (JavaAsyncScope owner = JavaAsyncScope.owningExecutorService(executor)) {
            CompletableFuture<?> invalid = observable(owner, completedPublisher(), null).collectAsync(0).toCompletableFuture();
            assertTrue(assertThrows(java.util.concurrent.ExecutionException.class,
                () -> invalid.get(3, TimeUnit.SECONDS)).getCause() instanceof IllegalArgumentException);
            assertFalse(invalid.isCancelled());
            WaitingPublisher publisher = new WaitingPublisher();
            AtomicBoolean callback = new AtomicBoolean();
            observable(owner, publisher, null).collect(1, 500,
                result -> callback.set(true), failure -> callback.set(true));
            assertTrue(publisher.entered.await(3, TimeUnit.SECONDS));
            assertTrue(publisher.cancelled.await(3, TimeUnit.SECONDS));
            executor.submit(() -> { }).get(3, TimeUnit.SECONDS);
            assertFalse(callback.get());
            observable(owner, completedPublisher(), null).collectAsync(1).toCompletableFuture()
                .get(3, TimeUnit.SECONDS).shouldSucceed();
        } finally { executor.shutdownNow(); }
    }

    private static AsyncQueryPerformerAdapter performer(boolean observable,
        java.util.function.Function<QueryContext, CompletionStage<?>> operation) {
        return new AsyncQueryPerformerAdapter(new AsyncQueryPerformer() {
            @Override public FullyQualifiedQueryName getFullyQualifiedName() { return NAME; }
            @Override public QueryDescriptor getDescriptor() {
                return new QueryDescriptor("values", "Tests.Async", "java.lang.String", List.of(), new RouteOptions(),
                    NAME.getValue(), List.of("Tests"), new AuthorizationMetadata(true), null,
                    QueryHttpMethodType.AUTO, observable ? QueryTransportType.OBSERVABLE : QueryTransportType.REQUEST_RESPONSE);
            }
            @Override public CompletionStage<?> perform(QueryContext context) { return operation.apply(context); }
        });
    }

    private static AsyncObservableQueryScenario<String> observable(JavaAsyncScope owner, Flow.Publisher<String> publisher,
        AtomicBoolean invoked) {
        return new AsyncObservableQueryScenario<>(new ObservableQueryScenario<>(performer(true, context -> {
            if (invoked != null) invoked.set(true);
            return CompletableFuture.completedFuture(publisher);
        })), owner);
    }

    private static Flow.Publisher<String> completedPublisher() {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean done;
            @Override public void request(long count) {
                if (done) return; done = true; subscriber.onNext("value"); subscriber.onComplete();
            }
            @Override public void cancel() { done = true; }
        });
    }

    private static void assertCancelled(CompletableFuture<?> future) {
        assertThrows(CancellationException.class, () -> future.get(3, TimeUnit.SECONDS));
        assertTrue(future.isCancelled());
    }

    private static final class WaitingPublisher implements Flow.Publisher<String> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        @Override public void subscribe(Flow.Subscriber<? super String> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) { entered.countDown(); }
                @Override public void cancel() { cancelled.countDown(); }
            });
        }
    }
}
