// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing.java

import io.cratis.arc.queries.ObservableQueryTransferMode
import io.cratis.arc.queries.QueryPaging
import io.cratis.arc.queries.QuerySortDirection
import io.cratis.arc.queries.QuerySorting
import io.cratis.arc.testing.ObservableQueryScenario
import io.cratis.arc.testing.ObservableQueryScenarioResult
import java.util.function.Consumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Cancellable handle returned by the Java observable scenario bridge. */
public fun interface ObservableQueryScenarioHandle {
    public fun cancel()
}

/** Java callback bridge for [ObservableQueryScenario]. */
public class AsyncObservableQueryScenario<TData>(
    private val scenario: ObservableQueryScenario<TData>,
    private val coroutineScope: CoroutineScope
) {
    /** Starts bounded collection and returns a handle that cancels upstream collection. */
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
        val job: Job = coroutineScope.launch {
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
