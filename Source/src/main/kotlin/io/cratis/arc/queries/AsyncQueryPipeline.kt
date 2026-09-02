// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.results.QueryResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Java-friendly bridge from the suspending [QueryPipeline] API to `CompletionStage`. */
public class AsyncQueryPipeline internal constructor(
    private val pipeline: QueryPipeline,
    private val coroutineScope: CoroutineScope
) {
    public companion object {
        /** Kotlin host-integration factory; Java callers should use JavaAsyncScope. */
        @JvmStatic
        @JvmSynthetic
        public fun fromCoroutineScope(
            pipeline: QueryPipeline,
            coroutineScope: CoroutineScope
        ): AsyncQueryPipeline = AsyncQueryPipeline(pipeline, coroutineScope)
    }

    /** Performs [request] asynchronously using the caller-owned coroutine scope. */
    public fun perform(
        request: QueryRequest,
        options: QueryExecutionOptions
    ): CompletionStage<QueryResult<*>> {
        val future = CompletableFuture<QueryResult<*>>()
        lateinit var job: Job
        job = coroutineScope.launch {
            try {
                future.complete(pipeline.perform(request, options))
            } catch (exception: CancellationException) {
                future.cancel(false)
                throw exception
            } catch (exception: Exception) {
                future.completeExceptionally(exception)
            }
        }
        future.whenComplete { _, _ ->
            if (future.isCancelled) job.cancel()
        }
        job.invokeOnCompletion { cause ->
            if (cause != null && !future.isDone) {
                if (cause is CancellationException) future.cancel(false) else future.completeExceptionally(cause)
            }
        }
        return future
    }
}
