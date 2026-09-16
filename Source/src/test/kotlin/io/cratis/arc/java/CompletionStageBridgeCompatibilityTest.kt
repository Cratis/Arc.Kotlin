// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import java.util.UUID
import io.cratis.arc.authentication.AsyncAuthentication
import io.cratis.arc.authentication.Authentication
import io.cratis.arc.authentication.AuthenticationRequestContext
import io.cratis.arc.authentication.AuthenticationResult
import io.cratis.arc.commands.AsyncCommandPipeline
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandPipeline
import io.cratis.arc.queries.AsyncQueryPipeline
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.ObservableQueryOpenResult
import io.cratis.arc.queries.ObservableQueryPipeline
import io.cratis.arc.queries.ObservableQueryTransferMode
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryPipeline
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.QueryResult
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CompletionStageBridgeCompatibilityTest {
    @ParameterizedTest
    @ValueSource(strings = ["execute", "validate", "query", "authentication", "observable"])
    fun `ordinary exception completes stage without cancelling ordinary parent or sibling`(kind: String): Unit = runBlocking {
        val parent = Job()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        try {
            val sibling = call(kind, scope) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned.complete(Unit) }
            }.toCompletableFuture()
            withTimeout(3000) { entered.await() }
            val failure = IllegalStateException("ordinary failure")
            val failed = call(kind, scope) { throw failure }.toCompletableFuture()
            assertSame(failure, assertThrows(ExecutionException::class.java) { failed.get(3, TimeUnit.SECONDS) }.cause)
            assertTrue(parent.isActive)
            assertFalse(sibling.isDone)
            sibling.cancel(false)
            withTimeout(3000) { cleaned.await() }
            assertTrue(parent.isActive)
        } finally { parent.cancelAndJoin() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["execute", "validate", "query", "authentication", "observable"])
    fun `cancellation and never started calls terminate stages without cancelling ordinary parent`(kind: String): Unit = runBlocking {
        val parent = Job()
        val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        try {
            val cancelled = call(kind, scope) { throw CancellationException("child") }.toCompletableFuture()
            assertThrows(CancellationException::class.java) { cancelled.get(3, TimeUnit.SECONDS) }
            assertTrue(cancelled.isCancelled)
            assertTrue(parent.isActive)
            parent.cancelAndJoin()
            var invoked = false
            val late = call(kind, scope) { invoked = true }.toCompletableFuture()
            assertThrows(CancellationException::class.java) { late.get(3, TimeUnit.SECONDS) }
            assertTrue(late.isCancelled)
            assertFalse(invoked)
        } finally { parent.cancelAndJoin() }
    }

    private fun call(kind: String, scope: CoroutineScope, operation: suspend () -> Unit): CompletionStage<*> {
        val command = object : CommandPipeline {
            override suspend fun execute(command: Any, options: CommandExecutionOptions): CommandResult<*> {
                operation()
                return CommandResult.success(options.correlationId)
            }
            override suspend fun validate(command: Any, options: CommandExecutionOptions): CommandResult<*> = execute(command, options)
        }
        val services = object : ServiceResolver { override fun <T : Any> resolve(type: Class<T>): T? = null }
        val commandOptions = CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), services)
        val queryOptions = QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), services)
        val request = QueryRequest(FullyQualifiedQueryName("Test.values"))
        return when (kind) {
            "execute" -> AsyncCommandPipeline.fromCoroutineScope(command, scope).execute("value", commandOptions)
            "validate" -> AsyncCommandPipeline.fromCoroutineScope(command, scope).validate("value", commandOptions)
            "query" -> AsyncQueryPipeline.fromCoroutineScope(object : QueryPipeline {
                override suspend fun perform(request: QueryRequest, options: QueryExecutionOptions): QueryResult<*> {
                    operation()
                    return QueryResult.success(options.correlationId, "value")
                }
            }, scope).perform(request, queryOptions)
            "authentication" -> AsyncAuthentication.fromCoroutineScope(object : Authentication {
                override val hasHandlers = true
                override suspend fun handleAuthentication(context: AuthenticationRequestContext): AuthenticationResult {
                    operation()
                    return AuthenticationResult.ANONYMOUS
                }
            }, scope).handleAuthentication(AuthenticationRequestContext())
            else -> AsyncObservableQueryPipeline(object : ObservableQueryPipeline {
                override suspend fun open(request: QueryRequest, options: QueryExecutionOptions,
                    transferMode: ObservableQueryTransferMode?, keyExtractor: ((Any) -> Any?)?): ObservableQueryOpenResult {
                    operation()
                    return ObservableQueryOpenResult.Failure(QueryResult.success(options.correlationId, "value"))
                }
            }, scope).open(request, queryOptions)
        }
    }
}
