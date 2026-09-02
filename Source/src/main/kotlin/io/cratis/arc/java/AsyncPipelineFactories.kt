// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.authentication.AsyncAuthentication
import io.cratis.arc.authentication.Authentication
import io.cratis.arc.commands.AsyncCommandPipeline
import io.cratis.arc.commands.CommandPipeline
import io.cratis.arc.queries.AsyncQueryPipeline
import io.cratis.arc.queries.ObservableQueryPipeline
import io.cratis.arc.queries.QueryHealth
import io.cratis.arc.queries.QueryHealthTracker
import io.cratis.arc.queries.QueryPipeline
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Flow as JdkFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel

/** AutoCloseable Java owner for the structured coroutine scope used by asynchronous Arc facades. */
public class JavaAsyncScope private constructor(
    executor: Executor,
    private val ownsExecutor: Boolean
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val dispatcher = executor.asCoroutineDispatcher()
    internal val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Creates a scope that never shuts down the caller-owned executor. */
    public companion object {
        @JvmStatic
        public fun usingExecutor(executor: Executor): JavaAsyncScope = JavaAsyncScope(executor, false)

        /** Creates a scope that shuts down the transferred ExecutorService when closed. */
        @JvmStatic
        public fun owningExecutorService(executor: ExecutorService): JavaAsyncScope = JavaAsyncScope(executor, true)
    }

    /** Creates a command facade using this scope. */
    public fun commands(pipeline: CommandPipeline): AsyncCommandPipeline =
        AsyncCommandPipeline(pipeline, activeScope())

    /** Creates a one-shot query facade using this scope. */
    public fun queries(pipeline: QueryPipeline): AsyncQueryPipeline =
        AsyncQueryPipeline(pipeline, activeScope())

    /** Creates an authentication facade using this scope. */
    public fun authentication(authentication: Authentication): AsyncAuthentication =
        AsyncAuthentication(authentication, activeScope())

    /** Creates an observable-query facade using this scope. */
    public fun observableQueries(pipeline: ObservableQueryPipeline): AsyncObservableQueryPipeline =
        AsyncObservableQueryPipeline(pipeline, activeScope())

    /** Creates a demand-aware publisher of query-health snapshots using this scope. */
    public fun queryHealth(tracker: QueryHealthTracker): JdkFlow.Publisher<QueryHealth> =
        CoroutineFlowPublisher(tracker.observe(), activeScope())

    /** Cancels all facade operations and, for an owned ExecutorService, shuts down the executor. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        coroutineScope.cancel()
        if (ownsExecutor) (dispatcher as ExecutorCoroutineDispatcher).close()
    }

    private fun activeScope(): CoroutineScope {
        check(!closed.get()) { "JavaAsyncScope is closed." }
        return coroutineScope
    }
}
