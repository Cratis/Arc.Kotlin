// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.commands.await
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryPipeline
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.QueryResult
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class BlockingPipelineTest {
    private val services = object : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
    private val options = CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal("worker", true), services, "tenant", "namespace")
    private val queryOptions = QueryExecutionOptions(options.correlationId, options.principal, services, "tenant", "namespace")
    private val request = QueryRequest(FullyQualifiedQueryName("Tests.query"))

    @Test
    fun `all operations preserve exact options results and caller thread`() {
        val caller = Thread.currentThread()
        val failure = CommandResult.error(options.correlationId, "failure")
        val validation = CommandResult.success(options.correlationId)
        val queryResult = QueryResult.unauthorized<Any>(options.correlationId)
        val command = Any()
        val commands = BlockingCommandPipeline(object : CommandPipeline {
            override suspend fun execute(command: Any, options: CommandExecutionOptions): CommandResult<*> {
                assertSame(this@BlockingPipelineTest.options, options)
                assertSame(caller, Thread.currentThread())
                return failure
            }
            override suspend fun validate(command: Any, options: CommandExecutionOptions): CommandResult<*> {
                assertSame(this@BlockingPipelineTest.options, options)
                return validation
            }
        }, options)
        val queries = BlockingQueryPipeline(queryPipeline { passed ->
            assertSame(queryOptions, passed)
            assertSame(caller, Thread.currentThread())
            queryResult
        }, queryOptions)
        assertSame(failure, commands.execute(command))
        assertSame(failure, commands.execute(command, options))
        assertSame(validation, commands.validate(command))
        assertSame(validation, commands.validate(command, options))
        assertSame(queryResult, queries.perform(request))
        assertSame(queryResult, queries.perform(request, queryOptions))
    }

    @Test
    fun `short calls require bound options and per call options override binding`() {
        val pipeline = commandPipeline { passed -> assertSame(options, passed); CommandResult.success(passed.correlationId) }
        val commands = BlockingCommandPipeline(pipeline)
        assertThrows(IllegalStateException::class.java) { commands.execute(Any()) }
        assertThrows(IllegalStateException::class.java) { commands.validate(Any()) }
        val queries = BlockingQueryPipeline(queryPipeline { QueryResult.success<Any>(it.correlationId) })
        assertThrows(IllegalStateException::class.java) { queries.perform(request) }
        val other = CommandExecutionOptions(UUID.randomUUID(), options.principal, services)
        assertTrue(BlockingCommandPipeline(pipeline, other).execute(Any(), options).isSuccess)
        assertTrue(BlockingCommandPipeline(pipeline, other).validate(Any(), options).isSuccess)
        val otherQuery = QueryExecutionOptions(UUID.randomUUID(), options.principal, services)
        assertEquals(queryOptions.correlationId, BlockingQueryPipeline(queryPipeline {
            assertSame(queryOptions, it)
            QueryResult.success<Any>(it.correlationId)
        }, otherQuery).perform(request, queryOptions).correlationId)
    }

    @Test
    fun `propagated exceptions and handler cancellation are not wrapped or converted`() {
        val failure = IllegalArgumentException("application failure")
        val cancellation = CancellationException("handler cancelled")
        for (exception in listOf(failure, cancellation)) {
            val commands = BlockingCommandPipeline(commandPipeline { throw exception }, options)
            assertSame(exception, assertThrows(exception.javaClass) { commands.execute(Any()) })
            assertSame(exception, assertThrows(exception.javaClass) { commands.validate(Any()) })
            val queries = BlockingQueryPipeline(queryPipeline { throw exception }, queryOptions)
            assertSame(exception, assertThrows(exception.javaClass) { queries.perform(request) })
        }
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun `guard rejects every operation after dispatcher migration and does not leak on thread reuse`(): Unit = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val commands = BlockingCommandPipeline(commandPipeline { CommandResult.success(it.correlationId) }, options)
            val queries = BlockingQueryPipeline(queryPipeline { QueryResult.success<Any>(it.correlationId) }, queryOptions)
            withContext(BlockingPipelineGuard.contextElement()) {
                withContext(dispatcher) {
                    assertThrows(IllegalStateException::class.java) { commands.execute(Any()) }
                    assertThrows(IllegalStateException::class.java) { commands.validate(Any()) }
                    assertThrows(IllegalStateException::class.java) { queries.perform(request) }
                }
            }
            withContext(dispatcher) {
                assertTrue(commands.execute(Any()).isSuccess)
                assertTrue(queries.perform(request).isSuccess)
            }
        }
    }

    @Test
    fun `nested facade rejected after suspension and marker restored after failure`() {
        val inner = BlockingQueryPipeline(queryPipeline { QueryResult.success<Any>(it.correlationId) }, queryOptions)
        val outer = BlockingCommandPipeline(commandPipeline {
            withContext(Dispatchers.Default) { inner.perform(request) }
            CommandResult.success(it.correlationId)
        }, options)
        assertThrows(IllegalStateException::class.java) { outer.execute(Any()) }
        assertTrue(inner.perform(request).isSuccess)
    }

    @Test
    fun `interrupt cancels awaited stage waits for migrated cleanup and restores flag for every operation`() {
        for (operation in 0..2) {
            val entered = CountDownLatch(1)
            val cleaning = CountDownLatch(1)
            val releaseCleanup = CompletableFuture<Unit>()
            val stage = CompletableFuture<Unit>()
            val cleaned = AtomicBoolean()
            val returned = AtomicBoolean()
            val failure = AtomicReference<Throwable>()
            val interruptedFlag = AtomicBoolean()
            suspend fun work() {
                withContext(Dispatchers.Default) {
                    try {
                        entered.countDown()
                        stage.await()
                    } finally {
                        withContext(NonCancellable) {
                            cleaning.countDown()
                            releaseCleanup.await()
                            cleaned.set(true)
                        }
                    }
                }
            }
            val commands = BlockingCommandPipeline(commandPipeline { work(); CommandResult.success(it.correlationId) }, options)
            val queries = BlockingQueryPipeline(queryPipeline { work(); QueryResult.success<Any>(it.correlationId) }, queryOptions)
            val thread = Thread {
                try {
                    when (operation) {
                        0 -> commands.execute(Any())
                        1 -> commands.validate(Any())
                        else -> queries.perform(request)
                    }
                } catch (exception: Throwable) {
                    failure.set(exception)
                    interruptedFlag.set(Thread.currentThread().isInterrupted)
                } finally {
                    returned.set(true)
                }
            }
            thread.start()
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                thread.interrupt()
                assertTrue(cleaning.await(5, TimeUnit.SECONDS))
                assertTrue(stage.isCancelled)
                assertFalse(returned.get(), "Must wait for cooperative cleanup")
                thread.interrupt() // Repeated interrupts must not detach cleanup either.
            } finally {
                releaseCleanup.complete(Unit)
                thread.join(5000)
            }
            assertFalse(thread.isAlive)
            assertTrue(cleaned.get())
            assertTrue(failure.get() is CancellationException)
            assertTrue(failure.get().cause is InterruptedException)
            assertTrue(interruptedFlag.get())
        }
    }

    @Test
    fun `already interrupted caller never starts work and retains interrupt flag`() {
        val called = AtomicBoolean()
        val commands = BlockingCommandPipeline(commandPipeline { called.set(true); CommandResult.success(it.correlationId) }, options)
        Thread.currentThread().interrupt()
        try {
            val failure = assertThrows(CancellationException::class.java) { commands.execute(Any()) }
            assertTrue(failure.cause is InterruptedException)
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(called.get())
        } finally {
            Thread.interrupted()
        }
    }

    private fun commandPipeline(block: suspend (CommandExecutionOptions) -> CommandResult<*>): CommandPipeline = object : CommandPipeline {
        override suspend fun execute(command: Any, options: CommandExecutionOptions): CommandResult<*> = block(options)
        override suspend fun validate(command: Any, options: CommandExecutionOptions): CommandResult<*> = block(options)
    }

    private fun queryPipeline(block: suspend (QueryExecutionOptions) -> QueryResult<*>): QueryPipeline = object : QueryPipeline {
        override suspend fun perform(request: QueryRequest, options: QueryExecutionOptions): QueryResult<*> {
            assertSame(this@BlockingPipelineTest.request, request)
            return block(options)
        }
    }
}
