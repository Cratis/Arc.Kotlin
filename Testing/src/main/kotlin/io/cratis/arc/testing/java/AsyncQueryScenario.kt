// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing.java

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryPaging
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QuerySortDirection
import io.cratis.arc.queries.QuerySorting
import io.cratis.arc.testing.QueryScenario
import io.cratis.arc.testing.QueryScenarioResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Java `CompletionStage` query bridge using a caller-owned bounded or structured [CoroutineScope]. */
public class AsyncQueryScenario<TData>(
    private val scenario: QueryScenario<TData>,
    private val coroutineScope: CoroutineScope
) {
    /** Creates a bridge for an exact query from a generated module. */
    public constructor(
        module: ArcArtifactModule,
        queryName: FullyQualifiedQueryName,
        coroutineScope: CoroutineScope
    ) : this(QueryScenario(module, queryName), coroutineScope)

    /** Creates a bridge for one real manual performer. */
    public constructor(performer: QueryPerformer, coroutineScope: CoroutineScope) :
        this(QueryScenario(performer), coroutineScope)

    /** Performs the query asynchronously. Cancellation of the returned future cancels its child job. */
    @JvmOverloads
    public fun perform(
        arguments: Map<String, Any?> = emptyMap(),
        paging: QueryPaging = QueryPaging(0, 0),
        sorting: QuerySorting = QuerySorting("", QuerySortDirection.ASCENDING)
    ): CompletionStage<QueryScenarioResult<TData>> = launch { scenario.perform(arguments, paging, sorting) }

    private fun launch(
        operation: suspend () -> QueryScenarioResult<TData>
    ): CompletionStage<QueryScenarioResult<TData>> {
        val future = CompletableFuture<QueryScenarioResult<TData>>()
        lateinit var job: Job
        job = coroutineScope.launch {
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
}
