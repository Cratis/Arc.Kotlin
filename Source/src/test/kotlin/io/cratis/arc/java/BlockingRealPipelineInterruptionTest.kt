// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandExecutionScope
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandValidator
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.DefaultCommandValidationFilter
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.commands.await
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.DefaultQueryPipeline
import io.cratis.arc.queries.DefaultQueryValidationFilter
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryValidator
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ModelValidationContext
import io.cratis.arc.validation.ModelValidator
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

internal class BlockingRealPipelineInterruptionTest {
    internal enum class Operation {
        COMMAND, QUERY, COMMAND_VALIDATION, VALIDATE_ONLY, QUERY_VALIDATION,
        COMMAND_MODEL, VALIDATE_MODEL, QUERY_MODEL
    }
    private class Work
    private val services = object : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
    private val options = CommandExecutionOptions(UUID.randomUUID(), ArcPrincipal("worker", true), services)
    private val queryOptions = QueryExecutionOptions(options.correlationId, options.principal, services)
    private val name = FullyQualifiedQueryName("Tests.query")

    @ParameterizedTest
    @EnumSource(Operation::class)
    fun `synchronous caller interruption cancels real pipeline after cleanup`(operation: Operation) {
        interruptedInvocation(operation, staged = false, worker = false)
    }

    @ParameterizedTest
    @EnumSource(Operation::class)
    fun `awaited stage interruption still cancels real pipeline after cleanup`(operation: Operation) {
        interruptedInvocation(operation, staged = true, worker = false)
    }

    @ParameterizedTest
    @EnumSource(Operation::class)
    fun `worker interruption is normalized without claiming physical caller interruption`(operation: Operation) {
        interruptedInvocation(operation, staged = false, worker = true)
    }

    private fun interruptedInvocation(operation: Operation, staged: Boolean, worker: Boolean) {
        val entered = CountDownLatch(1)
        val releaseWork = CountDownLatch(1)
        val cleaning = CountDownLatch(1)
        val releaseCleanup = CompletableFuture<Unit>()
        val stage = CompletableFuture<Unit>()
        val cleaned = AtomicBoolean()
        val returned = AtomicBoolean()
        val failure = AtomicReference<Throwable>()
        val flag = AtomicBoolean()
        val workerInterruption = InterruptedException("worker-originated, not caller-originated")
        val observedInterruption = AtomicReference<InterruptedException>()
        val fixture = Fixture(operation) {
            try {
                if (worker) {
                    withContext(Dispatchers.Default) {
                        assertFalse(Thread.currentThread().isInterrupted)
                        entered.countDown()
                        throw workerInterruption
                    }
                } else {
                    entered.countDown()
                    if (staged) stage.await() else releaseWork.await()
                }
            } catch (exception: InterruptedException) {
                // withContext may recover the worker's stack by copying its exception. The facade
                // must retain the exact exception delivered to the framework callback boundary.
                observedInterruption.set(exception)
                throw exception
            } finally {
                withContext(NonCancellable) {
                    cleaning.countDown()
                    releaseCleanup.await()
                    cleaned.set(true)
                }
            }
        }
        val thread = Thread {
            try {
                fixture.blocking()
            } catch (exception: Throwable) {
                failure.set(exception)
                flag.set(Thread.currentThread().isInterrupted)
            } finally {
                returned.set(true)
            }
        }
        thread.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            if (!worker) thread.interrupt()
            assertTrue(cleaning.await(5, TimeUnit.SECONDS))
            assertFalse(returned.get(), "Must wait for callback cleanup")
            if (staged) assertTrue(stage.isCancelled)
        } finally {
            releaseCleanup.complete(Unit)
            releaseWork.countDown()
            stage.cancel(true) // Release the fixture even if a cancellation assertion failed above.
            thread.join(5000)
        }
        assertFalse(thread.isAlive)
        assertTrue(cleaned.get())
        assertTrue(failure.get() is CancellationException, "Expected cancellation, got ${failure.get()}")
        assertTrue(failure.get().cause is InterruptedException)
        if (worker) assertSame(observedInterruption.get(), failure.get().cause)
        assertTrue(flag.get(), "Facade policy sets caller flag even for propagated worker interruption")
        fixture.assertCompletion()
    }

    @ParameterizedTest
    @EnumSource(Operation::class)
    fun `legacy and guard only coroutine invocations keep interruption as failure results`(operation: Operation): Unit = runBlocking {
        for (guarded in listOf(false, true)) {
            val fixture = Fixture(operation) { throw InterruptedException("legacy") }
            if (guarded) withContext(BlockingPipelineGuard.contextElement()) { fixture.nonblocking() }
            else fixture.nonblocking()
            assertFalse(Thread.currentThread().isInterrupted)
            fixture.assertCompletion()
        }
    }

    private inner class Fixture(val operation: Operation, val work: suspend () -> Unit) {
        val calls = AtomicInteger()
        val completion = mutableListOf<Int>()
        val handlers = ConcurrentCommandHandlerRegistry().apply {
            register(object : CommandHandler {
                override val commandType = Work::class.java
                override val metadata = CommandDescriptor("Work", Work::class.java.name)
                override suspend fun invoke(context: CommandContext): Any? {
                    calls.incrementAndGet()
                    if (operation == Operation.COMMAND) work()
                    return null
                }
            })
        }
        val modelValidator = object : ModelValidator<Work> {
            override val modelType = Work::class.java
            override suspend fun validate(model: Work, context: ModelValidationContext): List<ValidationResult> {
                work()
                return emptyList()
            }
        }
        val commandValidation = DefaultCommandValidationFilter(listOf(object : CommandValidator<Work> {
            override val commandType = Work::class.java
            override suspend fun validate(command: Work, context: CommandContext): List<ValidationResult> {
                if (operation == Operation.COMMAND_VALIDATION || operation == Operation.VALIDATE_ONLY) work()
                return emptyList()
            }
        }), emptyList(), if (operation == Operation.COMMAND_MODEL || operation == Operation.VALIDATE_MODEL) listOf(modelValidator) else emptyList())
        val commands = DefaultCommandPipeline(handlers, listOf(commandValidation), (1..2).map { index ->
            object : CommandExecutionScope {
                override fun begin(context: CommandContext) = Unit
                override suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>? {
                    assertFalse(result.isSuccess, "Begun scopes must roll back, not commit")
                    yield()
                    completion.add(index)
                    return null
                }
            }
        })
        val performers = ConcurrentQueryPerformerRegistry().apply {
            register(object : QueryPerformer {
                override val fullyQualifiedName = name
                override val descriptor = QueryDescriptor("query", "Tests", String::class.java.name)
                override suspend fun perform(context: QueryContext): Any? {
                    calls.incrementAndGet()
                    if (operation == Operation.QUERY) work()
                    return null
                }
            })
        }
        val queryValidation = DefaultQueryValidationFilter(listOf(object : QueryValidator {
            override val queryName = name
            override suspend fun validate(request: QueryRequest, context: QueryContext): List<ValidationResult> {
                if (operation == Operation.QUERY_VALIDATION) work()
                return emptyList()
            }
        }), emptyList(), if (operation == Operation.QUERY_MODEL) listOf(modelValidator) else emptyList())
        val queries = DefaultQueryPipeline(performers, listOf(queryValidation))

        fun blocking() {
            when (operation) {
                Operation.QUERY, Operation.QUERY_VALIDATION, Operation.QUERY_MODEL -> BlockingQueryPipeline(queries, queryOptions).perform(QueryRequest(name, mapOf("value" to Work())))
                Operation.VALIDATE_ONLY, Operation.VALIDATE_MODEL -> BlockingCommandPipeline(commands, options).validate(Work())
                else -> BlockingCommandPipeline(commands, options).execute(Work())
            }
        }

        suspend fun nonblocking() {
            when (operation) {
                Operation.QUERY, Operation.QUERY_VALIDATION, Operation.QUERY_MODEL -> assertFalse(queries.perform(QueryRequest(name, mapOf("value" to Work())), queryOptions).isSuccess)
                Operation.VALIDATE_ONLY, Operation.VALIDATE_MODEL -> assertFalse(commands.validate(Work(), options).isSuccess)
                else -> assertFalse(commands.execute(Work(), options).isSuccess)
            }
        }

        fun assertCompletion() {
            assertEquals(if (operation == Operation.COMMAND || operation == Operation.QUERY) 1 else 0, calls.get())
            assertEquals(if (operation == Operation.COMMAND || operation == Operation.COMMAND_VALIDATION || operation == Operation.COMMAND_MODEL) listOf(2, 1) else emptyList(), completion)
        }
    }
}
