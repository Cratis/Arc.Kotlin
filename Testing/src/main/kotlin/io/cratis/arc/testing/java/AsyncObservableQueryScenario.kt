// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing.java

import io.cratis.arc.queries.ObservableQueryTransferMode
import io.cratis.arc.queries.QueryPaging
import io.cratis.arc.queries.QuerySortDirection
import io.cratis.arc.queries.QuerySorting
import io.cratis.arc.testing.ObservableQueryScenario
import io.cratis.arc.testing.ObservableQueryScenarioResult
import io.cratis.arc.java.JavaAsyncScope
import io.cratis.arc.java.launchStage
import java.util.concurrent.CompletionStage
import java.util.function.Consumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Cancellable handle returned by the Java observable scenario bridge. */
public fun interface ObservableQueryScenarioHandle {
    public fun cancel()
}

/** Java stage and callback bridge borrowing a caller-owned scope; close the owner explicitly. */
public class AsyncObservableQueryScenario<TData> private constructor(
    private val scenario: ObservableQueryScenario<TData>,
    private val launch: (suspend () -> Unit) -> Job,
    private val launchStage: (suspend () -> ObservableQueryScenarioResult<TData>) -> CompletionStage<ObservableQueryScenarioResult<TData>>
) {
    /** Borrows a Kotlin host's bounded or structured scope. */
    public constructor(scenario: ObservableQueryScenario<TData>, coroutineScope: CoroutineScope) :
        this(scenario, { operation -> coroutineScope.launch { operation() } }, { coroutineScope.launchStage(it) })

    /** Borrows a Java owner. Calls after owner close cancel without executing user code or callbacks. */
    public constructor(scenario: ObservableQueryScenario<TData>, owner: JavaAsyncScope) :
        this(scenario, { owner.launch(it) }, { owner.launchStage(it) })

    /**
     * Collects at most [maximumEmissions] within one opening-and-collection timeout budget.
     * Timeout and cancellation cancel the stage and cooperative upstream work; cancelling the future
     * cancels only this child, not its owner or siblings. Execution follows the supplied executor.
     */
    @JvmOverloads
    public fun collectAsync(
        maximumEmissions: Int,
        timeoutMillis: Long = 5_000,
        arguments: Map<String, Any?> = emptyMap(),
        paging: QueryPaging = QueryPaging(0, 0),
        sorting: QuerySorting = QuerySorting("", QuerySortDirection.ASCENDING),
        transferMode: ObservableQueryTransferMode = ObservableQueryTransferMode.FULL
    ): CompletionStage<ObservableQueryScenarioResult<TData>> = launchStage {
        scenario.collect(maximumEmissions, timeoutMillis, arguments, paging, sorting, transferMode)
    }

    /**
     * Starts bounded collection and returns a handle that cancels upstream collection.
     * Cancellation, including timeout, invokes neither callback. Success-callback exceptions go to
     * the failure callback; failure-callback exceptions escape the coroutine.
     */
    @JvmOverloads
    public fun collect(
        maximumEmissions: Int,
        timeoutMillis: Long = 5_000,
        arguments: Map<String, Any?> = emptyMap(),
        paging: QueryPaging = QueryPaging(0, 0),
        sorting: QuerySorting = QuerySorting("", QuerySortDirection.ASCENDING),
        transferMode: ObservableQueryTransferMode = ObservableQueryTransferMode.FULL,
        onSuccess: Consumer<ObservableQueryScenarioResult<TData>>,
        onFailure: Consumer<Throwable>
    ): ObservableQueryScenarioHandle {
        val job: Job = launch {
            try {
                onSuccess.accept(scenario.collect(maximumEmissions, timeoutMillis, arguments, paging, sorting, transferMode))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                onFailure.accept(exception)
            }
        }
        return ObservableQueryScenarioHandle { job.cancel() }
    }
}
