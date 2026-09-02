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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** Java blocking query bridge backed by an owned, bounded coroutine scope. Close it after use. */
public class BlockingQueryScenario<TData> @JvmOverloads constructor(
    private val scenario: QueryScenario<TData>,
    parallelism: Int = 1
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val dispatcher: ExecutorCoroutineDispatcher
    private val coroutineScope: CoroutineScope

    init {
        require(parallelism > 0) { "parallelism must be greater than zero" }
        dispatcher = Executors.newFixedThreadPool(parallelism) { runnable ->
            Thread(runnable, "arc-query-scenario").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        coroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    /** Creates a bridge for an exact query from a generated module. */
    @JvmOverloads
    public constructor(
        module: ArcArtifactModule,
        queryName: FullyQualifiedQueryName,
        parallelism: Int = 1
    ) : this(QueryScenario(module, queryName), parallelism)

    /** Creates a bridge for one real manual performer. */
    @JvmOverloads
    public constructor(performer: QueryPerformer, parallelism: Int = 1) :
        this(QueryScenario(performer), parallelism)

    /** Performs the query and blocks the calling thread for the result. */
    @JvmOverloads
    public fun perform(
        arguments: Map<String, Any?> = emptyMap(),
        paging: QueryPaging = QueryPaging(0, 0),
        sorting: QuerySorting = QuerySorting("", QuerySortDirection.ASCENDING)
    ): QueryScenarioResult<TData> = waitFor { scenario.perform(arguments, paging, sorting) }

    /** Cancels owned work and closes the bounded dispatcher. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            coroutineScope.cancel()
            dispatcher.close()
        }
    }

    private fun <T> waitFor(operation: suspend () -> T): T {
        check(!closed.get()) { "The blocking query scenario is closed." }
        val deferred = coroutineScope.async { operation() }
        return runBlocking { deferred.await() }
    }
}
