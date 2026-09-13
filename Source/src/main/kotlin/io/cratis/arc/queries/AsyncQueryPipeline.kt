// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.java.launchStage
import io.cratis.arc.results.QueryResult
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CoroutineScope

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
    ): CompletionStage<QueryResult<*>> = coroutineScope.launchStage { pipeline.perform(request, options) }
}
