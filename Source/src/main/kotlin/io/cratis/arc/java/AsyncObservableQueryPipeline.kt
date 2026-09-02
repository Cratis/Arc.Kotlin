// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.queries.ObservableQueryOpenResult
import io.cratis.arc.queries.ObservableQueryPipeline
import io.cratis.arc.queries.ObservableQueryTransferMode
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.results.QueryResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow as JdkFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Java key extractor used for delta observable queries without exposing Kotlin Function types. */
public fun interface ObservableQueryKeyExtractor {
    public fun extractKey(value: Any): Any?
}

/** Java result of opening an observable query. */
public sealed interface AsyncObservableQueryOpenResult {
    /** Opening was rejected before a stream was created. */
    public class Failure(public val result: QueryResult<*>) : AsyncObservableQueryOpenResult

    /** Successfully opened demand-aware result stream. */
    public class Stream(public val results: JdkFlow.Publisher<QueryResult<*>>) : AsyncObservableQueryOpenResult
}

/** CompletionStage and JDK Flow bridge for Arc's observable query pipeline. */
public class AsyncObservableQueryPipeline internal constructor(
    private val pipeline: ObservableQueryPipeline,
    private val coroutineScope: CoroutineScope
) {

    /** Opens a full-transfer observable query. */
    public fun open(
        request: QueryRequest,
        options: QueryExecutionOptions
    ): CompletionStage<AsyncObservableQueryOpenResult> =
        open(request, options, ObservableQueryTransferMode.FULL, null)

    /** Opens an observable query with an explicit transfer mode. */
    public fun open(
        request: QueryRequest,
        options: QueryExecutionOptions,
        transferMode: ObservableQueryTransferMode
    ): CompletionStage<AsyncObservableQueryOpenResult> = open(request, options, transferMode, null)

    /** Opens an observable query without exposing suspend, Flow, or Kotlin function types. */
    public fun open(
        request: QueryRequest,
        options: QueryExecutionOptions,
        transferMode: ObservableQueryTransferMode,
        keyExtractor: ObservableQueryKeyExtractor?
    ): CompletionStage<AsyncObservableQueryOpenResult> = launchStage(coroutineScope) {
        val extractor: ((Any) -> Any?)? = keyExtractor?.let { javaExtractor ->
            { value -> javaExtractor.extractKey(value) }
        }
        when (val opened = pipeline.open(request, options, transferMode, extractor)) {
            is ObservableQueryOpenResult.Failure -> AsyncObservableQueryOpenResult.Failure(opened.result)
            is ObservableQueryOpenResult.Stream -> AsyncObservableQueryOpenResult.Stream(
                CoroutineFlowPublisher(opened.results, coroutineScope)
            )
        }
    }
}

private fun <T> launchStage(scope: CoroutineScope, operation: suspend () -> T): CompletionStage<T> {
    val future = CompletableFuture<T>()
    lateinit var job: Job
    job = scope.launch {
        try {
            future.complete(operation())
        } catch (exception: CancellationException) {
            future.cancel(false)
            throw exception
        } catch (exception: Exception) {
            future.completeExceptionally(exception)
        }
    }
    future.whenComplete { _, _ -> if (future.isCancelled) job.cancel() }
    job.invokeOnCompletion { cause ->
        if (cause != null && !future.isDone) {
            if (cause is CancellationException) future.cancel(false) else future.completeExceptionally(cause)
        }
    }
    return future
}

/** Cold publisher that starts one Flow collection per subscriber and emits only against positive demand. */
internal class CoroutineFlowPublisher<T : Any>(
    private val flow: Flow<T>,
    private val scope: CoroutineScope
) : JdkFlow.Publisher<T> {
    override fun subscribe(subscriber: JdkFlow.Subscriber<in T>) {
        val subscription = FlowSubscription(flow, scope, subscriber)
        try {
            subscriber.onSubscribe(subscription)
        } catch (_: Throwable) {
            subscription.cancel()
        }
    }

    private class FlowSubscription<T : Any>(
        private val flow: Flow<T>,
        scope: CoroutineScope,
        private val subscriber: JdkFlow.Subscriber<in T>
    ) : JdkFlow.Subscription {
        private val requested = AtomicLong()
        private val cancelled = AtomicBoolean()
        private val terminated = AtomicBoolean()
        private val demandChanged = Channel<Unit>(Channel.CONFLATED)
        private val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                flow.collect { value ->
                    awaitDemand()
                    if (cancelled.get()) return@collect
                    consumeDemand()
                    subscriber.onNext(value)
                }
                complete()
            } catch (exception: CancellationException) {
                if (!cancelled.get()) error(exception)
            } catch (exception: Throwable) {
                error(exception)
            }
        }

        override fun request(count: Long) {
            if (count <= 0) {
                error(IllegalArgumentException("Flow.Subscription.request requires a positive demand."))
                return
            }
            if (cancelled.get() || terminated.get()) return
            requested.getAndUpdate { current ->
                if (current == Long.MAX_VALUE || Long.MAX_VALUE - current < count) Long.MAX_VALUE else current + count
            }
            demandChanged.trySend(Unit)
            job.start()
        }

        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                demandChanged.close()
                job.cancel()
            }
        }

        private suspend fun awaitDemand() {
            while (requested.get() == 0L && !cancelled.get()) demandChanged.receive()
            job.ensureActive()
        }

        private fun consumeDemand() {
            requested.getAndUpdate { current -> if (current == Long.MAX_VALUE) current else current - 1 }
        }

        private fun complete() {
            if (!cancelled.get() && terminated.compareAndSet(false, true)) subscriber.onComplete()
        }

        private fun error(exception: Throwable) {
            if (terminated.compareAndSet(false, true)) {
                cancelled.set(true)
                demandChanged.close()
                job.cancel()
                subscriber.onError(exception)
            }
        }
    }
}
